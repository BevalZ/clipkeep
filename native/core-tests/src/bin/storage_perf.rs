//! Release-mode storage benchmark. Uses the production implementation and a disposable database.
use clipkeep_core_tests::db::{self, NewClip, RawClip, Scope};
use std::{hint::black_box, time::Instant};

fn measure(name: &str, repeats: usize, mut run: impl FnMut()) {
    run(); // Warm statement/page caches; report steady-state operation latency.
    let mut samples = Vec::with_capacity(repeats);
    for _ in 0..repeats {
        let start = Instant::now();
        run();
        samples.push(start.elapsed().as_secs_f64() * 1000.0);
    }
    samples.sort_by(f64::total_cmp);
    println!(
        "{name}: p50={:.3} ms p95={:.3} ms",
        samples[repeats / 2],
        samples[(repeats * 95 / 100).min(repeats - 1)]
    );
}

fn body(index: usize) -> String {
    // Shared prefixes/suffixes ensure preview-only lookup cannot stand in for exact equality.
    format!(
        "{}{:08}{}",
        "prefix 中😀 ".repeat(96),
        index,
        " suffix 中😀".repeat(96)
    )
}

fn main() {
    let root = std::env::args().nth(1).unwrap_or_else(|| ".".into());
    let count = std::env::args()
        .nth(2)
        .map(|v| v.parse().unwrap())
        .unwrap_or(10_000);
    let directory = tempfile::Builder::new()
        .prefix("clipkeep-perf-")
        .tempdir_in(root)
        .unwrap();
    let path = directory.path().join("history.db");
    let mut conn = db::open(&path).unwrap();
    let group = db::create_group(&conn, "benchmark").unwrap();
    let start = Instant::now();
    {
        let tx = conn.transaction().unwrap();
        for index in 0..count {
            let image = index % 10 == 0;
            let content = if image {
                format!("{:064x}.png", index)
            } else {
                body(index)
            };
            let hash = db::text_hash(&content);
            db::import_clip(
                &tx,
                &RawClip {
                    kind: if image { "image" } else { "text" }.into(),
                    size: content.len() as i64,
                    content,
                    hash,
                    width: None,
                    height: None,
                    favorite: index % 100 == 1,
                    pinned: index % 1000 == 1,
                    group_id: (index % 200 == 1).then_some(group.id),
                    created_at: index as i64,
                    updated_at: index as i64,
                },
            )
            .unwrap();
        }
        tx.commit().unwrap();
    }
    println!("rows={count}, seed={:.3} s", start.elapsed().as_secs_f64());
    let text = body(17);
    let hash = db::text_hash(&text);
    let clip = NewClip {
        kind: "text",
        content: &text,
        hash: &hash,
        width: None,
        height: None,
        size: text.len() as i64,
        source: None,
    };
    measure("save_duplicate_with_quota", 50, || {
        db::upsert(&conn, &clip, 500 * 1024 * 1024).unwrap();
    });
    {
        let tx = conn.unchecked_transaction().unwrap();
        measure("save_duplicate_inside_transaction", 100, || {
            db::upsert(&tx, &clip, 500 * 1024 * 1024).unwrap();
        });
        // Isolate SQL work from durable commit latency without changing production durability.
    }
    measure("storage_usage", 100, || {
        black_box(db::storage_usage(&conn).unwrap());
    });
    measure("missing_image_reference", 100, || {
        assert!(!db::is_file_referenced(&conn, "missing.png").unwrap());
    });
    measure("list_all", 30, || {
        assert_eq!(
            db::list(&conn, Scope::All, "").unwrap().len(),
            1000.min(count)
        );
    });
    measure("list_favorites", 30, || {
        black_box(db::list(&conn, Scope::Favorites, "").unwrap());
    });
    measure("list_group", 30, || {
        black_box(db::list(&conn, Scope::Group(group.id), "").unwrap());
    });
    measure("search_exact_substring", 10, || {
        assert_eq!(db::list(&conn, Scope::All, "00000017").unwrap().len(), 1);
    });
    measure("deduplicate_clean_history", 3, || {
        db::deduplicate(&conn).unwrap();
    });
    conn.execute_batch("PRAGMA wal_checkpoint(TRUNCATE)")
        .unwrap();
    println!("database_bytes={}", std::fs::metadata(&path).unwrap().len());
    drop(conn);
    measure("open_existing", 3, || {
        black_box(db::open(&path).unwrap());
    });
}
