use rusqlite::{Connection, params};
use std::sync::{Mutex, OnceLock};

static DB: OnceLock<Mutex<Connection>> = OnceLock::new();

fn get_db() -> &'static Mutex<Connection> {
    DB.get().expect("DB not initialized")
}

#[cfg(target_os = "android")]
#[no_mangle]
pub extern "system" fn Java_com_yourapp_trans_NativeEngine_initSystem(
    mut env: jni::JNIEnv,
    _class: jni::objects::JClass,
    db_path: jni::objects::JString,
) -> jni::sys::jboolean {
    android_logger::init_once(
        android_logger::Config::default().with_max_level(log::LevelFilter::Info)
    );

    let path: String = env.get_string(&db_path).unwrap().into();
    
    match Connection::open(&path) {
        Ok(conn) => {
            conn.execute_batch(
                "CREATE TABLE IF NOT EXISTS dictionary (
                    tr_word TEXT PRIMARY KEY, en_word TEXT NOT NULL
                );
                CREATE TABLE IF NOT EXISTS phrases (
                    tr_phrase TEXT PRIMARY KEY, en_phrase TEXT NOT NULL
                );"
            ).unwrap();
            seed_data(&conn);
            let _ = DB.set(Mutex::new(conn));
            log::info!("✅ DB başlatıldı");
            1
        }
        Err(e) => {
            log::error!("❌ DB hatası: {}", e);
            0
        }
    }
}

fn seed_data(conn: &Connection) {
    let words = vec![
        ("merhaba", "hello"), ("nasılsın", "how are you"),
        ("teşekkürler", "thank you"), ("evet", "yes"),
        ("hayır", "no"), ("lütfen", "please"),
        ("özür dilerim", "sorry"), ("güle güle", "goodbye"),
        ("ev", "house"), ("araba", "car"), ("su", "water"),
        ("yemek", "food"), ("para", "money"), ("zaman", "time"),
        ("bugün", "today"), ("yarın", "tomorrow"), ("dün", "yesterday"),
        ("ben", "I"), ("sen", "you"), ("o", "he"),
        ("biz", "we"), ("onlar", "they"),
        ("gitmek", "to go"), ("gelmek", "to come"),
        ("içmek", "to drink"), ("konuşmak", "to speak"),
        ("anlamak", "to understand"), ("bilmek", "to know"),
        ("iyi", "good"), ("kötü", "bad"),
        ("büyük", "big"), ("küçük", "small"),
        ("güzel", "beautiful"), ("hızlı", "fast"),
        ("yavaş", "slow"), ("sıcak", "hot"), ("soğuk", "cold"),
        ("nerede", "where"), ("ne zaman", "when"),
        ("neden", "why"), ("nasıl", "how"), ("kim", "who"), ("ne", "what"),
        ("müze", "museum"), ("otel", "hotel"),
        ("restoran", "restaurant"), ("havaalanı", "airport"),
        ("istasyon", "station"), ("bilet", "ticket"), ("fiyat", "price"),
    ];
    for (tr, en) in words {
        let _ = conn.execute(
            "INSERT OR IGNORE INTO dictionary (tr_word, en_word) VALUES (?1, ?2)",
            params![tr, en],
        );
    }
    let phrases = vec![
        ("nasılsın", "how are you"),
        ("iyiyim teşekkürler", "I'm fine thank you"),
        ("seni seviyorum", "I love you"),
        ("görüşürüz", "see you later"),
        ("hoş geldiniz", "welcome"),
        ("afiyet olsun", "enjoy your meal"),
        ("geçmiş olsun", "get well soon"),
        ("kolay gelsin", "good luck with your work"),
        ("ne kadar", "how much"),
        ("bu ne", "what is this"),
        ("tuvalet nerede", "where is the toilet"),
        ("yardım edin", "help me"),
        ("anlamıyorum", "I don't understand"),
        ("tekrar söyler misiniz", "can you repeat please"),
        ("kaçta kapanıyor", "what time does it close"),
        ("hesap lütfen", "the bill please"),
    ];
    for (tr, en) in phrases {
        let _ = conn.execute(
            "INSERT OR IGNORE INTO phrases (tr_phrase, en_phrase) VALUES (?1, ?2)",
            params![tr, en],
        );
    }
}

#[cfg(target_os = "android")]
#[no_mangle]
pub extern "system" fn Java_com_yourapp_trans_NativeEngine_translateLive(
    mut env: jni::JNIEnv,
    _class: jni::objects::JClass,
    input: jni::objects::JString,
    source_lang: jni::objects::JString,
    target_lang: jni::objects::JString,
) -> jni::sys::jstring {
    let input_str: String = env.get_string(&input).unwrap().into();
    let src: String = env.get_string(&source_lang).unwrap().into();
    let tgt: String = env.get_string(&target_lang).unwrap().into();

    let db = get_db().lock().unwrap();
    let translated = if src == "tr" && tgt == "en" {
        translate_tr_to_en(&db, &input_str)
    } else if src == "en" && tgt == "tr" {
        translate_en_to_tr(&db, &input_str)
    } else {
        input_str.clone()
    };
    let confidence = if translated != input_str { 0.9 } else { 0.5 };
    let json = format!(
        r#"{{"original":"{}","translated":"{}","confidence":{:.2}}}"#,
        input_str.replace("\"", "\\\""),
        translated.replace("\"", "\\\""),
        confidence
    );
    env.new_string(json).unwrap().into_raw()
}

fn translate_tr_to_en(db: &Connection, text: &str) -> String {
    let text_lower = text.to_lowercase().trim().to_string();
    if let Ok(phrase) = db.query_row(
        "SELECT en_phrase FROM phrases WHERE tr_phrase = ?1",
        params![text_lower], |row| row.get::<_, String>(0),
    ) { return phrase; }
    let words: Vec<&str> = text_lower.split_whitespace().collect();
    words.iter().map(|word| {
        let clean = word.trim_matches(|c: char| !c.is_alphanumeric());
        db.query_row("SELECT en_word FROM dictionary WHERE tr_word = ?1",
            params![clean], |row| row.get::<_, String>(0))
            .unwrap_or_else(|_| clean.to_string())
    }).collect::<Vec<_>>().join(" ")
}

fn translate_en_to_tr(db: &Connection, text: &str) -> String {
    let text_lower = text.to_lowercase().trim().to_string();
    if let Ok(phrase) = db.query_row(
        "SELECT tr_phrase FROM phrases WHERE en_phrase = ?1",
        params![text_lower], |row| row.get::<_, String>(0),
    ) { return phrase; }
    let words: Vec<&str> = text_lower.split_whitespace().collect();
    words.iter().map(|word| {
        let clean = word.trim_matches(|c: char| !c.is_alphanumeric());
        db.query_row("SELECT tr_word FROM dictionary WHERE en_word = ?1",
            params![clean], |row| row.get::<_, String>(0))
            .unwrap_or_else(|_| clean.to_string())
    }).collect::<Vec<_>>().join(" ")
}
