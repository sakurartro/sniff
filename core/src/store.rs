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
    /// in the library (Медиатека); unliked tracks stay known but hidden
    pub liked: bool,
    /// came from recommendations
    pub rec: bool,
}

pub struct Store(Connection);

const COLS: &str = "document_id,title,performer,duration,size,chat_id,message_id,liked,rec";

impl Store {
    pub async fn open(path: impl AsRef<Path>) -> Result<Self> {
        let c = Builder::new_local(path.as_ref()).build().await?.connect()?;
        c.execute_batch(
            "CREATE TABLE IF NOT EXISTS chats(id INTEGER PRIMARY KEY, title TEXT NOT NULL, selected INTEGER NOT NULL DEFAULT 0, cursor INTEGER NOT NULL DEFAULT 0);
             -- one row per document_id: forwarded copies in other chats collapse into one track
             CREATE TABLE IF NOT EXISTS tracks(document_id INTEGER PRIMARY KEY, title TEXT NOT NULL, performer TEXT NOT NULL, duration INTEGER NOT NULL, size INTEGER NOT NULL, chat_id INTEGER NOT NULL, message_id INTEGER NOT NULL);
             CREATE VIRTUAL TABLE IF NOT EXISTS tracks_fts USING fts5(title, performer, content='tracks', content_rowid='document_id');",
        )
        .await?;
        // migration for DBs created before LRU eviction; fails harmlessly when the column already exists
        let _ = c.execute("ALTER TABLE tracks ADD COLUMN last_played INTEGER NOT NULL DEFAULT 0", ()).await;
        // source bot kind: NULL = not checked yet, '' = not a usable bot, else inline/deeplink/text
        let _ = c.execute("ALTER TABLE chats ADD COLUMN bot TEXT", ()).await;
        // 1 = came from recommendations: never used as a seed, so taste does not drift rec -> rec -> rec
        let _ = c.execute("ALTER TABLE tracks ADD COLUMN rec INTEGER NOT NULL DEFAULT 0", ()).await;
        // ❤ = in the library; chat tracks start liked, recommendations do not
        if c.execute("ALTER TABLE tracks ADD COLUMN liked INTEGER NOT NULL DEFAULT 1", ()).await.is_ok() {
            c.execute("UPDATE tracks SET liked = 1 - rec", ()).await?;
        }
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
        self.0.execute("UPDATE tracks SET rec=1, liked=0 WHERE document_id=?1", params![document_id]).await?;
        Ok(())
    }

    pub async fn set_liked(&self, document_id: i64, liked: bool) -> Result<()> {
        self.0.execute("UPDATE tracks SET liked=?2 WHERE document_id=?1", params![document_id, liked as i64]).await?;
        Ok(())
    }

    /// Every known track, liked or not (what recommendations must not suggest again).
    pub async fn all_tracks(&self) -> Result<Vec<Track>> {
        let mut rows = self.0.query(&format!("SELECT {COLS} FROM tracks"), ()).await?;
        let mut out = vec![];
        while let Some(r) = rows.next().await? {
            out.push(row_track(&r)?);
        }
        Ok(out)
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
        let mut rows = self.0.query(&format!("SELECT {COLS} FROM tracks WHERE document_id=?1"), params![document_id]).await?;
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

    /// Empty query lists the library (liked, newest first); otherwise FTS prefix search over all tracks.
    pub async fn tracks(&self, query: &str) -> Result<Vec<Track>> {
        let q = query.split_whitespace().map(|w| format!("\"{}\"*", w.replace('"', ""))).collect::<Vec<_>>().join(" ");
        let mut rows = if q.is_empty() {
            self.0.query(&format!("SELECT {COLS} FROM tracks WHERE liked=1 ORDER BY message_id DESC"), ()).await?
        } else {
            self.0
                .query(
                    "SELECT t.document_id,t.title,t.performer,t.duration,t.size,t.chat_id,t.message_id,t.liked,t.rec FROM tracks t JOIN tracks_fts f ON f.rowid=t.document_id WHERE tracks_fts MATCH ?1 ORDER BY rank",
                    params![q],
                )
                .await?
        };
        let mut out = vec![];
        while let Some(r) = rows.next().await? {
            out.push(row_track(&r)?);
        }
        Ok(out)
    }
}

fn row_track(r: &Row) -> Result<Track> {
    Ok(Track { document_id: r.get(0)?, title: r.get(1)?, performer: r.get(2)?, duration: r.get(3)?, size: r.get::<i64>(4)? as u64, chat_id: r.get(5)?, message_id: r.get(6)?, liked: r.get::<i64>(7)? != 0, rec: r.get::<i64>(8)? != 0 })
}

#[cfg(test)]
mod tests {
    use super::*;

    fn t(id: i64, title: &str, chat: i64) -> Track {
        Track { document_id: id, title: title.into(), performer: "Band".into(), duration: 100, size: 1, chat_id: chat, message_id: id as i32, liked: true, rec: false }
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
        assert_eq!(s.last_played().await.unwrap()[&10], 0);
        // a recommendation is known but not in the library until liked
        s.mark_rec(11).await.unwrap();
        assert_eq!(s.tracks("").await.unwrap().len(), 1);
        assert_eq!(s.tracks("other").await.unwrap().len(), 1); // search sees it
        assert_eq!(s.all_tracks().await.unwrap().len(), 2);
        s.set_liked(11, true).await.unwrap();
        assert!(s.track(11).await.unwrap().unwrap().liked);
        // an unliked chat track survives a re-sync unliked
        s.set_liked(10, false).await.unwrap();
        assert!(!s.add_track(&t(10, "Hello World", 1)).await.unwrap());
        assert!(!s.track(10).await.unwrap().unwrap().liked);
        s.touch(10).await.unwrap();
        assert!(s.last_played().await.unwrap()[&10] > 0);
    }
}
