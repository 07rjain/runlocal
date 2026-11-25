use jni::JNIEnv;
use jni::objects::{JObject, JString};
use jni::sys::{jlong, jstring};
use polyg2p::{G2p, Lang};
use std::path::Path;
use std::ptr;

#[unsafe(no_mangle)]
pub extern "system" fn Java_app_pocket_local_BlueG2p_open(
    mut env: JNIEnv<'_>,
    _: JObject<'_>,
    directory: JString<'_>,
) -> jlong {
    let directory: String = match env.get_string(&directory) {
        Ok(path) => path.into(),
        Err(_) => return 0,
    };
    let value = std::panic::catch_unwind(|| {
        G2p::new(Some(Path::new(&directory)))
    });
    match value {
        Ok(g2p) => Box::into_raw(Box::new(g2p)) as jlong,
        Err(_) => {
            let _ = env.throw_new("java/lang/IllegalStateException", "Could not read the BlueV3 pronunciation files.");
            0
        }
    }
}

#[unsafe(no_mangle)]
pub extern "system" fn Java_app_pocket_local_BlueG2p_phonemize(
    mut env: JNIEnv<'_>,
    _: JObject<'_>,
    handle: jlong,
    text: JString<'_>,
) -> jstring {
    let text: String = match env.get_string(&text) {
        Ok(value) => value.into(),
        Err(_) => return ptr::null_mut(),
    };
    let value = std::panic::catch_unwind(|| {
        // The speech worker owns the handle and closes it after inference.
        unsafe { &*(handle as *const G2p) }.phonemize(&text, Lang::En)
    });
    match value {
        Ok(ipa) => env.new_string(ipa).map_or(ptr::null_mut(), JString::into_raw),
        Err(_) => {
            let _ = env.throw_new("java/lang/IllegalStateException", "BlueV3 could not pronounce the text.");
            ptr::null_mut()
        }
    }
}

#[unsafe(no_mangle)]
pub extern "system" fn Java_app_pocket_local_BlueG2p_close(_: JNIEnv<'_>, _: JObject<'_>, handle: jlong) {
    if handle != 0 {
        unsafe { drop(Box::from_raw(handle as *mut G2p)) };
    }
}
