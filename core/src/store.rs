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
}

pub struct Store(Connection);

const COLS: &str = "document_id,title,performer,duration,size,chat_id,message_id";

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
                "INSERT OR IGNORE INTO tracks VALUES(?1,?2,?3,?4,?5,?6,?7)",
                params![t.document_id, t.title.as_str(), t.performer.as_str(), t.duration, t.size as i64, t.chat_id, t.message_id],
            )
            .await?;
        if n > 0 {
            self.0.execute("INSERT INTO tracks_fts(rowid,title,performer) VALUES(?1,?2,?3)", params![t.document_id, t.title.as_str(), t.performer.as_str()]).await?;
        }
        Ok(n > 0)
    }

    pub async fn track(&self, document_id: i64) -> Result<Option<Track>> {
        let mut rows = self.0.query(&format!("SELECT {COLS} FROM tracks WHERE document_id=?1"), params![document_id]).await?;
        rows.next().await?.map(|r| row_track(&r)).transpose()
    }

    /// Empty query lists everything (newest first); otherwise FTS prefix search.
    pub async fn tracks(&self, query: &str) -> Result<Vec<Track>> {
        let q = query.split_whitespace().map(|w| format!("\"{}\"*", w.replace('"', ""))).collect::<Vec<_>>().join(" ");
        let mut rows = if q.is_empty() {
            self.0.query(&format!("SELECT {COLS} FROM tracks ORDER BY message_id DESC"), ()).await?
        } else {
            self.0
                .query(
                    "SELECT t.document_id,t.title,t.performer,t.duration,t.size,t.chat_id,t.message_id FROM tracks t JOIN tracks_fts f ON f.rowid=t.document_id WHERE tracks_fts MATCH ?1 ORDER BY rank",
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
    Ok(Track { document_id: r.get(0)?, title: r.get(1)?, performer: r.get(2)?, duration: r.get(3)?, size: r.get::<i64>(4)? as u64, chat_id: r.get(5)?, message_id: r.get(6)? })
}

#[cfg(test)]
mod tests {
    use super::*;

    fn t(id: i64, title: &str, chat: i64) -> Track {
        Track { document_id: id, title: title.into(), performer: "Band".into(), duration: 100, size: 1, chat_id: chat, message_id: id as i32 }
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
    }
}
