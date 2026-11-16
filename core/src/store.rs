use libsql::{params, Builder, Connection, Row};
use std::path::Path;

pub type Result<T> = std::result::Result<T, libsql::Error>;

#[derive(Debug, Clone, PartialEq)]
pub struct Track {
    pub document_id: i64,
    pub title: String,
    pub performer: String,
    pub duration: u32,
    pub size: u64,
    pub chat_id: i64,
    pub message_id: i32,
    /// ❤: in the «Мне нравится» playlist (computed, not a column)
    pub liked: bool,
    /// a recommendation nobody adopted yet (adding it to a playlist clears it)
    pub rec: bool,
    /// ✕ «Не нравится»: out of the library, recommendations and shuffle; stays in playlists
    pub disliked: bool,
}

/// Built-in playlist behind ❤, cannot be renamed or deleted.
pub const LIKED: i64 = 1;

#[derive(Debug, Clone, PartialEq)]
pub struct Playlist {
    pub id: i64,
    pub name: String,
    /// custom cover image path; None = collage of the first tracks
    pub cover: Option<String>,
    pub count: u32,
}

pub struct Store(Connection);

/// Track columns, the table aliased as `t`.
const COLS: &str = "t.document_id,t.title,t.performer,t.duration,t.size,t.chat_id,t.message_id,EXISTS(SELECT 1 FROM playlist_tracks l WHERE l.playlist_id=1 AND l.document_id=t.document_id),t.rec,t.disliked";

impl Store {
    pub async fn open(path: impl AsRef<Path>) -> Result<Self> {
        let c = Builder::new_local(path.as_ref()).build().await?.connect()?;
        c.execute_batch(
            "CREATE TABLE IF NOT EXISTS chats(id INTEGER PRIMARY KEY, title TEXT NOT NULL, selected INTEGER NOT NULL DEFAULT 0, cursor INTEGER NOT NULL DEFAULT 0);
             -- one row per document_id: forwarded copies in other chats collapse into one track
             CREATE TABLE IF NOT EXISTS tracks(document_id INTEGER PRIMARY KEY, title TEXT NOT NULL, performer TEXT NOT NULL, duration INTEGER NOT NULL, size INTEGER NOT NULL, chat_id INTEGER NOT NULL, message_id INTEGER NOT NULL);
             CREATE VIRTUAL TABLE IF NOT EXISTS tracks_fts USING fts5(title, performer, content='tracks', content_rowid='document_id');
             CREATE TABLE IF NOT EXISTS playlists(id INTEGER PRIMARY KEY, name TEXT NOT NULL, cover TEXT);
             -- one row per (playlist, track): no duplicates; pos orders the playlist
             CREATE TABLE IF NOT EXISTS playlist_tracks(playlist_id INTEGER NOT NULL, document_id INTEGER NOT NULL, pos INTEGER NOT NULL, PRIMARY KEY(playlist_id, document_id));
             INSERT OR IGNORE INTO playlists(id,name) VALUES(1,'Мне нравится');",
        )
        .await?;
        // migration for DBs created before LRU eviction; fails harmlessly when the column already exists
        let _ = c.execute("ALTER TABLE tracks ADD COLUMN last_played INTEGER NOT NULL DEFAULT 0", ()).await;
        // source bot kind: NULL = not checked yet, '' = not a usable bot, else inline/deeplink/text
        let _ = c.execute("ALTER TABLE chats ADD COLUMN bot TEXT", ()).await;
        // 1 = the app already turned this source bot's notifications off once (never again, the user may undo it)
        let _ = c.execute("ALTER TABLE chats ADD COLUMN muted INTEGER NOT NULL DEFAULT 0", ()).await;
        // 1 = came from recommendations: never used as a seed, so taste does not drift rec -> rec -> rec
        let _ = c.execute("ALTER TABLE tracks ADD COLUMN rec INTEGER NOT NULL DEFAULT 0", ()).await;
        // the old `liked` column (❤ = library) is left unused: ❤ now is the «Мне нравится» playlist, started empty
        let _ = c.execute("ALTER TABLE tracks ADD COLUMN disliked INTEGER NOT NULL DEFAULT 0", ()).await;
        Ok(Self(c))
    }

    pub async fn upsert_chat(&self, id: i64, title: &str) -> Result<()> {
        self.0.execute("INSERT INTO chats(id,title) VALUES(?1,?2) ON CONFLICT(id) DO UPDATE SET title=?2", params![id, title]).await?;
        Ok(())
    }

    /// Returns false if the chat is unknown (run `chats` first).
    pub async fn set_selected(&self, id: i64, selected: bool) -> Result<bool> {
        Ok(self.0.execute("UPDATE chats SET selected=?2 WHERE id=?1", params![id, selected as i64]).await? > 0)
    }

    /// (id, title, selected)
    pub async fn chats(&self) -> Result<Vec<(i64, String, bool)>> {
        let mut rows = self.0.query("SELECT id,title,selected FROM chats ORDER BY title", ()).await?;
        let mut out = vec![];
        while let Some(r) = rows.next().await? {
            out.push((r.get(0)?, r.get(1)?, r.get::<i64>(2)? != 0));
        }
        Ok(out)
    }

    pub async fn selected_chats(&self) -> Result<Vec<i64>> {
        let mut rows = self.0.query("SELECT id FROM chats WHERE selected=1", ()).await?;
        let mut out = vec![];
        while let Some(r) = rows.next().await? {
            out.push(r.get(0)?);
        }
        Ok(out)
    }

    /// Selected chats with their bot kind (None = not checked yet).
    pub async fn selected_bots(&self) -> Result<Vec<(i64, Option<String>)>> {
        let mut rows = self.0.query("SELECT id,bot FROM chats WHERE selected=1", ()).await?;
        let mut out = vec![];
        while let Some(r) = rows.next().await? {
            out.push((r.get(0)?, r.get(1)?));
        }
        Ok(out)
    }

    /// Has the app muted this bot chat already?
    pub async fn muted(&self, chat: i64) -> Result<bool> {
        let mut rows = self.0.query("SELECT muted FROM chats WHERE id=?1", params![chat]).await?;
        Ok(match rows.next().await? { Some(r) => r.get::<i64>(0)? != 0, None => false })
    }

    pub async fn set_muted(&self, chat: i64) -> Result<()> {
        self.0.execute("UPDATE chats SET muted=1 WHERE id=?1", params![chat]).await?;
        Ok(())
    }

    pub async fn set_bot(&self, chat: i64, kind: &str) -> Result<()> {
        self.0.execute("UPDATE chats SET bot=?2 WHERE id=?1", params![chat, kind]).await?;
        Ok(())
    }

    /// Lowest message id already scanned (0 = nothing yet); backfill continues below it.
    pub async fn cursor(&self, chat: i64) -> Result<i32> {
        let mut rows = self.0.query("SELECT cursor FROM chats WHERE id=?1", params![chat]).await?;
        Ok(rows.next().await?.map(|r| r.get::<i32>(0)).transpose()?.unwrap_or(0))
    }

    pub async fn set_cursor(&self, chat: i64, cursor: i32) -> Result<()> {
        self.0.execute("UPDATE chats SET cursor=?2 WHERE id=?1", params![chat, cursor]).await?;
        Ok(())
    }

    /// Returns true if the track was new.
    pub async fn add_track(&self, t: &Track) -> Result<bool> {
        let n = self
            .0
            .execute(
                "INSERT OR IGNORE INTO tracks(document_id,title,performer,duration,size,chat_id,message_id) VALUES(?1,?2,?3,?4,?5,?6,?7)",
                params![t.document_id, t.title.as_str(), t.performer.as_str(), t.duration, t.size as i64, t.chat_id, t.message_id],
            )
            .await?;
        if n > 0 {
            self.0.execute("INSERT INTO tracks_fts(rowid,title,performer) VALUES(?1,?2,?3)", params![t.document_id, t.title.as_str(), t.performer.as_str()]).await?;
        }
        Ok(n > 0)
    }

    pub async fn mark_rec(&self, document_id: i64) -> Result<()> {
        self.0.execute("UPDATE tracks SET rec=1 WHERE document_id=?1", params![document_id]).await?;
        Ok(())
    }

    pub async fn set_disliked(&self, document_id: i64, disliked: bool) -> Result<()> {
        self.0.execute("UPDATE tracks SET disliked=?2 WHERE document_id=?1", params![document_id, disliked as i64]).await?;
        Ok(())
    }

    /// Every known track (what recommendations must not suggest again).
    pub async fn all_tracks(&self) -> Result<Vec<Track>> {
        self.list(&format!("SELECT {COLS} FROM tracks t"), ()).await
    }

    async fn list(&self, sql: &str, p: impl libsql::params::IntoParams) -> Result<Vec<Track>> {
        let mut rows = self.0.query(sql, p).await?;
        let mut out = vec![];
        while let Some(r) = rows.next().await? {
            out.push(row_track(&r)?);
        }
        Ok(out)
    }

    /// «Мне нравится» first, then by creation.
    pub async fn playlists(&self) -> Result<Vec<Playlist>> {
        let mut rows = self.0.query("SELECT p.id,p.name,p.cover,(SELECT COUNT(*) FROM playlist_tracks x WHERE x.playlist_id=p.id) FROM playlists p ORDER BY p.id", ()).await?;
        let mut out = vec![];
        while let Some(r) = rows.next().await? {
            out.push(Playlist { id: r.get(0)?, name: r.get(1)?, cover: r.get(2)?, count: r.get::<i64>(3)? as u32 });
        }
        Ok(out)
    }

    pub async fn playlist_tracks(&self, id: i64) -> Result<Vec<Track>> {
        self.list(&format!("SELECT {COLS} FROM playlist_tracks p JOIN tracks t ON t.document_id=p.document_id WHERE p.playlist_id=?1 ORDER BY p.pos"), params![id]).await
    }

    /// Returns the new playlist's id.
    pub async fn create_playlist(&self, name: &str) -> Result<i64> {
        self.0.execute("INSERT INTO playlists(name) VALUES(?1)", params![name]).await?;
        Ok(self.0.last_insert_rowid())
    }

    pub async fn rename_playlist(&self, id: i64, name: &str) -> Result<()> {
        self.0.execute("UPDATE playlists SET name=?2 WHERE id=?1 AND id<>1", params![id, name]).await?;
        Ok(())
    }

    pub async fn set_playlist_cover(&self, id: i64, cover: Option<String>) -> Result<()> {
        self.0.execute("UPDATE playlists SET cover=?2 WHERE id=?1", params![id, cover]).await?;
        Ok(())
    }

    /// The tracks themselves stay known; «Мне нравится» cannot be deleted.
    pub async fn delete_playlist(&self, id: i64) -> Result<()> {
        if id == LIKED {
            return Ok(());
        }
        self.0.execute("DELETE FROM playlist_tracks WHERE playlist_id=?1", params![id]).await?;
        self.0.execute("DELETE FROM playlists WHERE id=?1", params![id]).await?;
        Ok(())
    }

    /// Adding puts the track at the end (at the top of «Мне нравится», newest first) and adopts it:
    /// no longer a recommendation, no longer disliked. Adding twice is a no-op.
    pub async fn set_in_playlist(&self, id: i64, document_id: i64, on: bool) -> Result<()> {
        if !on {
            self.0.execute("DELETE FROM playlist_tracks WHERE playlist_id=?1 AND document_id=?2", params![id, document_id]).await?;
            return Ok(());
        }
        let pos = if id == LIKED { "COALESCE(MIN(pos),0)-1" } else { "COALESCE(MAX(pos),0)+1" };
        self.0
            .execute(&format!("INSERT OR IGNORE INTO playlist_tracks(playlist_id,document_id,pos) SELECT ?1,?2,{pos} FROM playlist_tracks WHERE playlist_id=?1"), params![id, document_id])
            .await?;
        self.0.execute("UPDATE tracks SET rec=0, disliked=0 WHERE document_id=?1", params![document_id]).await?;
        Ok(())
    }

    /// Ids of the playlists holding the track (the ✓ marks of the add sheet).
    pub async fn playlists_with(&self, document_id: i64) -> Result<Vec<i64>> {
        let mut rows = self.0.query("SELECT playlist_id FROM playlist_tracks WHERE document_id=?1", params![document_id]).await?;
        let mut out = vec![];
        while let Some(r) = rows.next().await? {
            out.push(r.get(0)?);
        }
        Ok(out)
    }

    /// New order of the playlist after editing; tracks not listed are removed.
    pub async fn set_playlist_order(&self, id: i64, document_ids: &[i64]) -> Result<()> {
        let tx = self.0.transaction().await?;
        tx.execute("DELETE FROM playlist_tracks WHERE playlist_id=?1", params![id]).await?;
        for (i, d) in document_ids.iter().enumerate() {
            tx.execute("INSERT OR IGNORE INTO playlist_tracks(playlist_id,document_id,pos) VALUES(?1,?2,?3)", params![id, *d, i as i64]).await?;
        }
        tx.commit().await
    }

    /// Marks the track as just used (LRU cache eviction order).
    pub async fn touch(&self, document_id: i64) -> Result<()> {
        self.0.execute("UPDATE tracks SET last_played=CAST(strftime('%s','now') AS INTEGER) WHERE document_id=?1", params![document_id]).await?;
        Ok(())
    }

    /// document_id -> last_played (unix seconds, 0 = never).
    pub async fn last_played(&self) -> Result<std::collections::HashMap<i64, i64>> {
        let mut rows = self.0.query("SELECT document_id,last_played FROM tracks", ()).await?;
        let mut out = std::collections::HashMap::new();
        while let Some(r) = rows.next().await? {
            out.insert(r.get(0)?, r.get(1)?);
        }
        Ok(out)
    }

    pub async fn track(&self, document_id: i64) -> Result<Option<Track>> {
        let mut rows = self.0.query(&format!("SELECT {COLS} FROM tracks t WHERE t.document_id=?1"), params![document_id]).await?;
        rows.next().await?.map(|r| row_track(&r)).transpose()
    }

    /// Every track's performer string (for artist spelling statistics).
    pub async fn performers(&self) -> Result<Vec<String>> {
        let mut rows = self.0.query("SELECT performer FROM tracks", ()).await?;
        let mut out = vec![];
        while let Some(r) = rows.next().await? {
            out.push(r.get(0)?);
        }
        Ok(out)
    }

    /// Empty query lists the library (newest first): chat tracks plus everything in a playlist, minus ✕;
    /// what source bots deliver (recommendations, search downloads) joins it only via a playlist.
    /// Otherwise FTS prefix search over all tracks.
    pub async fn tracks(&self, query: &str) -> Result<Vec<Track>> {
        let q = query.split_whitespace().map(|w| format!("\"{}\"*", w.replace('"', ""))).collect::<Vec<_>>().join(" ");
        if q.is_empty() {
            return self
                .list(
                    &format!(
                        "SELECT {COLS} FROM tracks t WHERE t.disliked=0 AND ((t.rec=0 AND t.chat_id NOT IN (SELECT id FROM chats WHERE bot IS NOT NULL AND bot<>'')) \
                         OR EXISTS(SELECT 1 FROM playlist_tracks p WHERE p.document_id=t.document_id)) ORDER BY t.message_id DESC"
                    ),
                    (),
                )
                .await;
        }
        self.list(&format!("SELECT {COLS} FROM tracks t JOIN tracks_fts f ON f.rowid=t.document_id WHERE tracks_fts MATCH ?1 ORDER BY rank"), params![q]).await
    }
}

fn row_track(r: &Row) -> Result<Track> {
    Ok(Track { document_id: r.get(0)?, title: r.get(1)?, performer: r.get(2)?, duration: r.get(3)?, size: r.get::<i64>(4)? as u64, chat_id: r.get(5)?, message_id: r.get(6)?, liked: r.get::<i64>(7)? != 0, rec: r.get::<i64>(8)? != 0, disliked: r.get::<i64>(9)? != 0 })
}

#[cfg(test)]
mod tests {
    use super::*;

    fn t(id: i64, title: &str, chat: i64) -> Track {
        Track { document_id: id, title: title.into(), performer: "Band".into(), duration: 100, size: 1, chat_id: chat, message_id: id as i32, liked: false, rec: false, disliked: false }
    }

    fn ids(v: Vec<Track>) -> Vec<i64> {
        v.into_iter().map(|t| t.document_id).collect()
    }

    #[tokio::test]
    async fn dedup_search_cursor() {
        let d = tempfile::tempdir().unwrap();
        let s = Store::open(d.path().join("db")).await.unwrap();
        s.upsert_chat(1, "A").await.unwrap();
        assert!(s.add_track(&t(10, "Hello World", 1)).await.unwrap());
        assert!(!s.add_track(&t(10, "Hello World", 2)).await.unwrap()); // same document, other chat
        assert!(s.add_track(&t(11, "Other", 1)).await.unwrap());
        assert_eq!(s.tracks("hel").await.unwrap().len(), 1);
        assert_eq!(s.tracks("band").await.unwrap().len(), 2);
        assert_eq!(s.tracks("").await.unwrap().len(), 2);
        s.set_cursor(1, 5).await.unwrap();
        assert_eq!(s.cursor(1).await.unwrap(), 5);
        assert!(s.set_selected(1, true).await.unwrap());
        assert!(!s.set_selected(99, true).await.unwrap());
        assert_eq!(s.selected_chats().await.unwrap(), vec![1]);
        assert_eq!(s.selected_bots().await.unwrap(), vec![(1, None)]);
        s.set_bot(1, "inline").await.unwrap();
        assert_eq!(s.selected_bots().await.unwrap(), vec![(1, Some("inline".into()))]);
        s.set_bot(1, "").await.unwrap(); // checked: not a bot
        assert_eq!(s.last_played().await.unwrap()[&10], 0);
        // a recommendation is known but not in the library until put in a playlist
        s.mark_rec(11).await.unwrap();
        assert_eq!(s.tracks("").await.unwrap().len(), 1);
        assert_eq!(s.tracks("other").await.unwrap().len(), 1); // search sees it
        assert_eq!(s.all_tracks().await.unwrap().len(), 2);
        // tracks from a source bot chat stay out of the library too
        s.upsert_chat(2, "Bot").await.unwrap();
        s.set_bot(2, "inline").await.unwrap();
        s.add_track(&t(12, "Fetched", 2)).await.unwrap();
        assert_eq!(s.tracks("").await.unwrap().len(), 1);
        // ❤ = «Мне нравится»: adopts the rec, newest on top, no duplicates
        s.set_in_playlist(LIKED, 11, true).await.unwrap();
        s.set_in_playlist(LIKED, 10, true).await.unwrap();
        s.set_in_playlist(LIKED, 11, true).await.unwrap();
        let t11 = s.track(11).await.unwrap().unwrap();
        assert!(t11.liked && !t11.rec);
        assert_eq!(ids(s.playlist_tracks(LIKED).await.unwrap()), vec![10, 11]);
        assert_eq!(s.tracks("").await.unwrap().len(), 2);
        // own playlists append at the end, keep an edited order, and are deletable
        let p = s.create_playlist("Mix").await.unwrap();
        for d in [12, 10] {
            s.set_in_playlist(p, d, true).await.unwrap();
        }
        assert_eq!(ids(s.playlist_tracks(p).await.unwrap()), vec![12, 10]);
        assert_eq!(s.tracks("").await.unwrap().len(), 3); // the bot track is in a playlist now
        assert_eq!(s.playlists_with(10).await.unwrap(), vec![LIKED, p]);
        s.set_playlist_order(p, &[10, 12]).await.unwrap();
        assert_eq!(ids(s.playlist_tracks(p).await.unwrap()), vec![10, 12]);
        s.set_in_playlist(p, 12, false).await.unwrap();
        s.rename_playlist(p, "Mix 2").await.unwrap();
        s.rename_playlist(LIKED, "x").await.unwrap(); // ignored
        let pl = s.playlists().await.unwrap();
        assert_eq!((pl[0].name.as_str(), pl[1].name.as_str(), pl[1].count), ("Мне нравится", "Mix 2", 1));
        s.delete_playlist(LIKED).await.unwrap(); // ignored
        s.delete_playlist(p).await.unwrap();
        assert_eq!(s.playlists().await.unwrap().len(), 1);
        // ✕ hides from the library but keeps the playlist; adding again clears it
        s.set_disliked(10, true).await.unwrap();
        assert!(!ids(s.tracks("").await.unwrap()).contains(&10));
        assert_eq!(s.playlist_tracks(LIKED).await.unwrap().len(), 2);
        assert!(!s.add_track(&t(10, "Hello World", 1)).await.unwrap()); // survives a re-sync
        assert!(s.track(10).await.unwrap().unwrap().disliked);
        s.touch(10).await.unwrap();
        assert!(s.last_played().await.unwrap()[&10] > 0);
    }
}
