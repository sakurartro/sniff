use anyhow::Result;
use std::io::{self, Write};
use sniff_core::{keys::ApiKeys, store::Store, tg::{SignIn, Tg}};

fn prompt(msg: &str) -> Result<String> {
    print!("{msg}");
    io::stdout().flush()?;
    let mut s = String::new();
    io::stdin().read_line(&mut s)?;
    Ok(s.trim().to_string())
}

const HELP: &str = "usage: sniff <login | chats | select <chat_id> | unselect <chat_id> | sync [pages] | songs [query] | get <document_id> | cover [document_id] | recs <seed document_id...> | bottest <bot> <artist> <title>>\nenv: SNIFF_DIR (data dir), TG_API_ID + TG_API_HASH (optional own keys), LASTFM_API_KEY";

#[tokio::main]
async fn main() -> Result<()> {
    let args: Vec<String> = std::env::args().skip(1).collect();
    let Some(cmd) = args.first().map(String::as_str) else {
        println!("{HELP}");
        return Ok(());
    };
    let dir = std::path::PathBuf::from(std::env::var("SNIFF_DIR").unwrap_or_else(|_| ".sniff".into()));
    std::fs::create_dir_all(dir.join("cache"))?;
    let store = Store::open(dir.join("library.db")).await?;

    // offline commands first: no network needed
    match cmd {
        "select" | "unselect" => {
            if !store.set_selected(args[1].parse()?, cmd == "select").await? {
                anyhow::bail!("unknown chat id: run `chats` first and use an id from its list");
            }
            return Ok(());
        }
        "songs" => {
            for t in store.tracks(args.get(1).map(String::as_str).unwrap_or("")).await? {
                println!("{:>20}  {} — {}  [{}:{}]", t.document_id, t.performer, t.title, fmt_dur(t.duration), t.chat_id);
            }
            return Ok(());
        }
        _ => {}
    }

    let user = match (std::env::var("TG_API_ID"), std::env::var("TG_API_HASH")) {
        (Ok(id), Ok(hash)) => Some(ApiKeys { id: id.parse()?, hash }),
        _ => None,
    };
    let tg = Tg::connect(dir.join("session.sqlite"), ApiKeys::resolve(user)?).await?;

    match cmd {
        "login-link" => {
            if tg.is_authorized().await? {
                println!("already logged in");
                return Ok(());
            }
            println!("Open the link on a phone with Telegram, or scan the QR from another device (Settings > Devices).");
            let r = tg
                .login_by_token(|link| {
                    if let Ok(q) = qrcode::QrCode::new(link) {
                        println!("{}", q.render::<qrcode::render::unicode::Dense1x2>().build());
                    }
                    println!("{link}\n");
                })
                .await?;
            if let SignIn::PasswordRequired(p) = r {
                if tg.check_password(p, &prompt("2FA password (echoed!): ")?).await?.is_some() {
                    anyhow::bail!("wrong password");
                }
            }
            println!("ok");
        }
        "probe" => println!("{}", tg.probe_code(&args[1]).await?),
        "login" => {
            if tg.is_authorized().await? {
                println!("already logged in");
                return Ok(());
            }
            let token = tg.request_code(&prompt("phone (+...): ")?).await?;
            if let SignIn::PasswordRequired(p) = tg.sign_in(&token, &prompt("code: ")?).await? {
                if tg.check_password(p, &prompt("2FA password (echoed!): ")?).await?.is_some() {
                    anyhow::bail!("wrong password");
                }
            }
            println!("ok");
        }
        _ if !tg.is_authorized().await? => println!("not logged in: run `login`"),
        "chats" => {
            tg.refresh_chats(&store).await?;
            for (id, title, sel) in store.chats().await? {
                println!("{} {:>16}  {title}", if sel { "[x]" } else { "[ ]" }, id);
            }
        }
        "sync" => {
            let pages: usize = args.get(1).map(|p| p.parse()).transpose()?.unwrap_or(1);
            let chats = store.selected_chats().await?;
            if chats.is_empty() {
                println!("no chats selected: run `chats`, then `select <id>`");
            }
            for chat in chats {
                for _ in 0..pages {
                    let (new, end) = tg.backfill_page(&store, chat, 100).await?;
                    println!("chat {chat}: +{new} tracks{}", if end { " (history done)" } else { "" });
                    if end {
                        break;
                    }
                }
            }
        }
        "get" => {
            eprintln!("downloading...");
            println!("{}", tg.download(&store, args[1].parse()?, &dir.join("cache")).await?.display());
        }
        "cover" => {
            // `cover <id>` fetches one; `cover` alone reports thumb coverage over the first 30 tracks
            let ids: Vec<i64> = match args.get(1) {
                Some(id) => vec![id.parse()?],
                None => store.tracks("").await?.iter().take(30).map(|t| t.document_id).collect(),
            };
            let (mut have, dir) = (0, dir.join("covers"));
            std::fs::create_dir_all(&dir)?;
            for id in &ids {
                let c = tg.cover(&store, *id, &dir).await?;
                have += c.is_some() as usize;
                println!("{id}: {}", c.map_or("no thumb".into(), |p| p.display().to_string()));
            }
            println!("{have}/{} tracks have a thumb", ids.len());
        }
        "recs" => {
            // `recs <document_id...>`: seeds, most recent last; needs a selected source bot chat
            let mut seeds = vec![];
            for id in &args[1..] {
                seeds.push(store.track(id.parse()?).await?.ok_or_else(|| anyhow::anyhow!("unknown track {id}"))?);
            }
            match sniff_core::recs::Recs::default().next(&tg, &store, &seeds).await? {
                Some(t) => println!("{:>20}  {} — {}  [{}:{}]", t.document_id, t.performer, t.title, fmt_dur(t.duration), t.chat_id),
                None => println!("nothing found"),
            }
        }
        "bottest" => {
            // `bottest <@bot> <artist> <title>`: connects the bot like the app does, times search and download
            let (bot, c) = (&args[1], sniff_core::recs::Cand { artist: args[2].clone(), title: args[3].clone(), duration: 0 });
            let kind = tg.connect_bot(&store, bot).await?;
            let chat = store.selected_bots().await?.into_iter().rev().find(|(_, k)| k.as_deref() == Some(kind.as_str())).map(|(id, _)| id).unwrap();
            println!("{bot}: kind={kind}");
            let t0 = std::time::Instant::now();
            let got = if kind == "text" {
                tg.fetch(&store, chat, &kind, &c).await?
            } else {
                let hits = tg.search_bot(chat, &format!("{} {}", c.artist, c.title), 15).await?;
                println!("search: {} hits in {:.1}s", hits.len(), t0.elapsed().as_secs_f32());
                for h in hits.iter().take(5) {
                    println!("  {} — {} [{}] payload={:?}", h.cand.artist, h.cand.title, fmt_dur(h.cand.duration), h.payload);
                }
                let t1 = std::time::Instant::now();
                let r = match hits.first() { Some(h) => tg.fetch_hit(&store, chat, h).await?, None => None };
                println!("tap->audio: {:.1}s", t1.elapsed().as_secs_f32());
                r
            };
            match got {
                Some(t) => println!("got {} — {} [{}] in {:.1}s total", t.performer, t.title, fmt_dur(t.duration), t0.elapsed().as_secs_f32()),
                None => println!("nothing in {:.1}s", t0.elapsed().as_secs_f32()),
            }
        }
        _ => println!("{HELP}"),
    }
    Ok(())
}

fn fmt_dur(s: u32) -> String {
    format!("{}:{:02}", s / 60, s % 60)
}
