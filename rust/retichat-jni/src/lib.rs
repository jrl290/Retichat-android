//! JNI bridge for Retichat Android.
//!
//! This crate produces a `cdylib` (`libretichat_jni.so`) loaded via
//! `System.loadLibrary("retichat_jni")` in Kotlin.
//!
//! Every public function follows the JNI naming convention:
//!   Java_com_retichat_app_bridge_RetichatBridge_<methodName>
//!
//! ## Handle conventions
//!
//! Opaque `u64` handles are passed as `jlong` across the JNI boundary.
//! A return value of `0` indicates an error – call `nativeLastError()` to
//! retrieve the message.
//!
//! ## Thread safety
//!
//! The Rust-side handle registry and all `Arc<Mutex<_>>` wrappers are
//! thread-safe.  The delivery callback is dispatched from a Rust background
//! thread, so it attaches to the JVM before calling Kotlin.

use std::sync::{Arc, Mutex};

use jni::objects::{GlobalRef, JByteArray, JClass, JObject, JString, JValue};
use jni::sys::{jbyteArray, jfloat, jint, jlong, jstring};
use jni::{JNIEnv, JavaVM};

use lxmf_rust::ffi as lxmf;
use lxmf_rust::lx_message::LXMessage;
use reticulum_rust::destination::{Destination, DestinationType};
use reticulum_rust::ffi as rns;
use reticulum_rust::identity::Identity;
use reticulum_rust::lxstamper::LXStamper;
use reticulum_rust::packet::Packet;
use reticulum_rust::transport::Transport;
use sha2::{Digest, Sha256};

// ---------------------------------------------------------------------------
// Android logcat output
// ---------------------------------------------------------------------------
extern "C" {
    fn __android_log_write(prio: i32, tag: *const u8, text: *const u8) -> i32;
}

/// Write a message to Android logcat under the "RNS" tag (priority = INFO = 4).
fn android_log(msg: &str) {
    let tag = b"RNS\0";
    let mut buf = msg.as_bytes().to_vec();
    buf.push(0); // null-terminate
    unsafe {
        __android_log_write(4, tag.as_ptr(), buf.as_ptr());
    }
}

// ---------------------------------------------------------------------------
// Helpers
// ---------------------------------------------------------------------------

/// Convert a Rust `Result` into a JNI handle (0 on error, sets last-error).
fn ok_or_zero(r: Result<u64, String>) -> jlong {
    match r {
        Ok(h) => h as jlong,
        Err(e) => {
            rns::set_error(e);
            0
        }
    }
}

/// Convert a Rust `Result<(), String>` into a JNI int (0 ok, -1 error).
fn ok_or_neg(r: Result<(), String>) -> jint {
    match r {
        Ok(()) => 0,
        Err(e) => {
            rns::set_error(e);
            -1
        }
    }
}

/// Extract a Rust `String` from a JNI `JString`.
fn jstring_to_string(env: &mut JNIEnv, s: &JString) -> String {
    env.get_string(s)
        .map(|js| js.into())
        .unwrap_or_default()
}

/// Extract a `Vec<u8>` from a JNI byte array.
fn jbytes_to_vec(env: &JNIEnv, arr: &JByteArray) -> Vec<u8> {
    env.convert_byte_array(arr).unwrap_or_default()
}

/// Create a JNI byte array from a `&[u8]`.
fn vec_to_jbytes(env: &JNIEnv, data: &[u8]) -> jbyteArray {
    let out = env.new_byte_array(data.len() as i32).unwrap();
    let _ = env.set_byte_array_region(&out, 0, unsafe {
        std::slice::from_raw_parts(data.as_ptr() as *const i8, data.len())
    });
    out.into_raw()
}

// ---------------------------------------------------------------------------
// Stored callback (delivery)
// ---------------------------------------------------------------------------

static DELIVERY_CB: Mutex<Option<(JavaVM, GlobalRef)>> = Mutex::new(None);
static ANNOUNCE_CB: Mutex<Option<(JavaVM, GlobalRef)>> = Mutex::new(None);

/// Process-wide APP_LINK status callback (mirrors iOS
/// `lxmf_app_link_register_status_callback`).  Only one callback is
/// supported on the Kotlin side; replacing it overwrites the prior ref.
static APP_LINK_STATUS_CB: Mutex<Option<(JavaVM, GlobalRef)>> = Mutex::new(None);

// ---------------------------------------------------------------------------
// JNI entry points
// ---------------------------------------------------------------------------

/// `RetichatBridge.nativeInit(configDir: String, logLevel: Int): Int`
#[no_mangle]
pub extern "system" fn Java_com_retichat_app_bridge_RetichatBridge_nativeInit(
    mut env: JNIEnv,
    _class: JClass,
    config_dir: JString,
    log_level: jint,
) -> jint {
    android_log("nativeInit: setting log callback");
    // Route Rust log() output to Android logcat before init
    rns::set_log_callback(|msg| {
        android_log(&msg);
    });
    android_log("nativeInit: log callback set, calling init");

    let dir = jstring_to_string(&mut env, &config_dir);
    let result = rns::init(&dir, log_level);
    android_log(&format!("nativeInit: init result={:?}", result));

    // Re-apply log callback after init in case init() reset LOG_STATE
    rns::set_log_callback(|msg| {
        android_log(&msg);
    });
    android_log("nativeInit: log callback re-applied after init");

    ok_or_neg(result)
}

/// `RetichatBridge.nativeShutdown(): Int`
#[no_mangle]
pub extern "system" fn Java_com_retichat_app_bridge_RetichatBridge_nativeShutdown(
    _env: JNIEnv,
    _class: JClass,
) -> jint {
    ok_or_neg(rns::shutdown())
}

/// `RetichatBridge.nativeLastError(): String?`
#[no_mangle]
pub extern "system" fn Java_com_retichat_app_bridge_RetichatBridge_nativeLastError(
    mut env: JNIEnv,
    _class: JClass,
) -> jstring {
    match rns::take_error() {
        Some(msg) => env
            .new_string(&msg)
            .map(|s| s.into_raw())
            .unwrap_or(std::ptr::null_mut()),
        None => std::ptr::null_mut(),
    }
}

// ---- Identity ----

/// `RetichatBridge.nativeIdentityCreate(): Long`
#[no_mangle]
pub extern "system" fn Java_com_retichat_app_bridge_RetichatBridge_nativeIdentityCreate(
    _env: JNIEnv,
    _class: JClass,
) -> jlong {
    ok_or_zero(rns::identity_create())
}

/// `RetichatBridge.nativeIdentityFromFile(path: String): Long`
#[no_mangle]
pub extern "system" fn Java_com_retichat_app_bridge_RetichatBridge_nativeIdentityFromFile(
    mut env: JNIEnv,
    _class: JClass,
    path: JString,
) -> jlong {
    let p = jstring_to_string(&mut env, &path);
    ok_or_zero(rns::identity_from_file(&p))
}

/// `RetichatBridge.nativeIdentityFromBytes(bytes: ByteArray): Long`
#[no_mangle]
pub extern "system" fn Java_com_retichat_app_bridge_RetichatBridge_nativeIdentityFromBytes(
    env: JNIEnv,
    _class: JClass,
    bytes: JByteArray,
) -> jlong {
    let b = jbytes_to_vec(&env, &bytes);
    ok_or_zero(rns::identity_from_bytes(&b))
}

/// `RetichatBridge.nativeIdentityToFile(handle: Long, path: String): Int`
#[no_mangle]
pub extern "system" fn Java_com_retichat_app_bridge_RetichatBridge_nativeIdentityToFile(
    mut env: JNIEnv,
    _class: JClass,
    handle: jlong,
    path: JString,
) -> jint {
    let p = jstring_to_string(&mut env, &path);
    ok_or_neg(rns::identity_to_file(handle as u64, &p))
}

/// `RetichatBridge.nativeIdentityPublicKey(handle: Long): ByteArray?`
#[no_mangle]
pub extern "system" fn Java_com_retichat_app_bridge_RetichatBridge_nativeIdentityPublicKey(
    env: JNIEnv,
    _class: JClass,
    handle: jlong,
) -> jbyteArray {
    match rns::identity_public_key(handle as u64) {
        Ok(bytes) => vec_to_jbytes(&env, &bytes),
        Err(e) => {
            rns::set_error(e);
            std::ptr::null_mut()
        }
    }
}

/// `RetichatBridge.nativeIdentityHash(handle: Long): ByteArray?`
#[no_mangle]
pub extern "system" fn Java_com_retichat_app_bridge_RetichatBridge_nativeIdentityHash(
    env: JNIEnv,
    _class: JClass,
    handle: jlong,
) -> jbyteArray {
    match rns::identity_hash(handle as u64) {
        Ok(bytes) => vec_to_jbytes(&env, &bytes),
        Err(e) => {
            rns::set_error(e);
            std::ptr::null_mut()
        }
    }
}

/// `RetichatBridge.nativeIdentityDestroy(handle: Long): Int`
#[no_mangle]
pub extern "system" fn Java_com_retichat_app_bridge_RetichatBridge_nativeIdentityDestroy(
    _env: JNIEnv,
    _class: JClass,
    handle: jlong,
) -> jint {
    ok_or_neg(rns::identity_destroy(handle as u64))
}

// ---- Destination ----

/// `RetichatBridge.nativeDestinationHash(idHandle: Long, appName: String, aspects: String): ByteArray?`
///
/// `aspects` is a comma-separated string, e.g. `"delivery"`.
#[no_mangle]
pub extern "system" fn Java_com_retichat_app_bridge_RetichatBridge_nativeDestinationHash(
    mut env: JNIEnv,
    _class: JClass,
    id_handle: jlong,
    app_name: JString,
    aspects: JString,
) -> jbyteArray {
    let app = jstring_to_string(&mut env, &app_name);
    let asp = jstring_to_string(&mut env, &aspects);
    let parts: Vec<&str> = asp.split(',').map(|s| s.trim()).filter(|s| !s.is_empty()).collect();

    match rns::destination_hash_for(id_handle as u64, &app, &parts) {
        Ok(h) => vec_to_jbytes(&env, &h),
        Err(e) => {
            rns::set_error(e);
            std::ptr::null_mut()
        }
    }
}

// ---- Transport ----

/// `RetichatBridge.nativeTransportHasPath(destHash: ByteArray): Boolean`
#[no_mangle]
pub extern "system" fn Java_com_retichat_app_bridge_RetichatBridge_nativeTransportHasPath(
    env: JNIEnv,
    _class: JClass,
    dest_hash: JByteArray,
) -> jint {
    let h = jbytes_to_vec(&env, &dest_hash);
    if rns::transport_has_path(&h) { 1 } else { 0 }
}

/// `RetichatBridge.nativeTransportRequestPath(destHash: ByteArray): Int`
#[no_mangle]
pub extern "system" fn Java_com_retichat_app_bridge_RetichatBridge_nativeTransportRequestPath(
    env: JNIEnv,
    _class: JClass,
    dest_hash: JByteArray,
) -> jint {
    let h = jbytes_to_vec(&env, &dest_hash);
    ok_or_neg(rns::transport_request_path(&h))
}

/// `RetichatBridge.nativeTransportHopsTo(destHash: ByteArray): Int`
#[no_mangle]
pub extern "system" fn Java_com_retichat_app_bridge_RetichatBridge_nativeTransportHopsTo(
    env: JNIEnv,
    _class: JClass,
    dest_hash: JByteArray,
) -> jint {
    let h = jbytes_to_vec(&env, &dest_hash);
    rns::transport_hops_to(&h)
}

// ---- Router ----

/// `RetichatBridge.nativeRouterCreate(identityHandle: Long, storagePath: String): Long`
#[no_mangle]
pub extern "system" fn Java_com_retichat_app_bridge_RetichatBridge_nativeRouterCreate(
    mut env: JNIEnv,
    _class: JClass,
    identity_handle: jlong,
    storage_path: JString,
) -> jlong {
    let sp = jstring_to_string(&mut env, &storage_path);
    ok_or_zero(lxmf::router_create(identity_handle as u64, &sp))
}

/// `RetichatBridge.nativeRouterRegisterDelivery(router: Long, identity: Long, name: String, stampCost: Int): Long`
#[no_mangle]
pub extern "system" fn Java_com_retichat_app_bridge_RetichatBridge_nativeRouterRegisterDelivery(
    mut env: JNIEnv,
    _class: JClass,
    router: jlong,
    identity: jlong,
    name: JString,
    stamp_cost: jint,
) -> jlong {
    let n = jstring_to_string(&mut env, &name);
    let cost = if stamp_cost < 0 { None } else { Some(stamp_cost as u32) };
    let display = if n.is_empty() { None } else { Some(n.as_str()) };
    ok_or_zero(lxmf::router_register_delivery(router as u64, identity as u64, display, cost))
}

/// `RetichatBridge.nativeRouterSetDeliveryCallback(routerHandle: Long, callback: MessageCallback): Int`
///
/// `MessageCallback` is a Kotlin interface with:
/// ```kotlin
/// fun onMessage(hash: ByteArray, srcHash: ByteArray, destHash: ByteArray,
///               title: String, content: String, timestamp: Double,
///               signatureValid: Boolean)
/// ```
#[no_mangle]
pub extern "system" fn Java_com_retichat_app_bridge_RetichatBridge_nativeRouterSetDeliveryCallback(
    mut env: JNIEnv,
    _class: JClass,
    router: jlong,
    callback: JObject,
) -> jint {
    // Store JVM + global ref to callback object
    let jvm = match env.get_java_vm() {
        Ok(vm) => vm,
        Err(e) => {
            rns::set_error(format!("Failed to get JavaVM: {}", e));
            return -1;
        }
    };
    let global_ref = match env.new_global_ref(&callback) {
        Ok(r) => r,
        Err(e) => {
            rns::set_error(format!("Failed to create global ref: {}", e));
            return -1;
        }
    };

    *DELIVERY_CB.lock().unwrap() = Some((jvm, global_ref));

    let result = lxmf::router_set_delivery_callback(
        router as u64,
        Arc::new(move |msg: lxmf::ReceivedMessage| {
            let guard = DELIVERY_CB.lock().unwrap();
            let (jvm, cb_ref) = match guard.as_ref() {
                Some(pair) => pair,
                None => return,
            };

            // Attach current thread to JVM
            let mut env = match jvm.attach_current_thread() {
                Ok(env) => env,
                Err(_) => return,
            };

            // Build arguments
            let j_hash = env.byte_array_from_slice(&msg.hash).unwrap();
            let j_src = env.byte_array_from_slice(&msg.source_hash).unwrap();
            let j_dest = env.byte_array_from_slice(&msg.destination_hash).unwrap();
            let j_title = env.new_string(&msg.title).unwrap();
            let j_content = env.new_string(&msg.content).unwrap();

            // Raw LXMF fields (msgpack bytes — Kotlin decodes)
            let j_fields = env.byte_array_from_slice(&msg.fields_raw).unwrap();

            let _ = env.call_method(
                cb_ref.as_obj(),
                "onMessage",
                "([B[B[BLjava/lang/String;Ljava/lang/String;DZ[B)V",
                &[
                    JValue::Object(&j_hash),
                    JValue::Object(&j_src),
                    JValue::Object(&j_dest),
                    JValue::Object(&JObject::from(j_title)),
                    JValue::Object(&JObject::from(j_content)),
                    JValue::Double(msg.timestamp),
                    JValue::Bool(msg.signature_validated as u8),
                    JValue::Object(&j_fields),
                ],
            );
        }),
    );

    ok_or_neg(result)
}

/// `RetichatBridge.nativeRouterSetAnnounceCallback(routerHandle: Long, callback: AnnounceCallback): Int`
///
/// `AnnounceCallback` is a Kotlin interface with:
/// ```kotlin
/// fun onAnnounce(destHash: ByteArray, displayName: String?)
/// ```
#[no_mangle]
pub extern "system" fn Java_com_retichat_app_bridge_RetichatBridge_nativeRouterSetAnnounceCallback(
    mut env: JNIEnv,
    _class: JClass,
    router: jlong,
    callback: JObject,
) -> jint {
    let jvm = match env.get_java_vm() {
        Ok(vm) => vm,
        Err(e) => {
            rns::set_error(format!("Failed to get JavaVM: {}", e));
            return -1;
        }
    };
    let global_ref = match env.new_global_ref(&callback) {
        Ok(r) => r,
        Err(e) => {
            rns::set_error(format!("Failed to create global ref: {}", e));
            return -1;
        }
    };

    *ANNOUNCE_CB.lock().unwrap() = Some((jvm, global_ref));

    let result = lxmf::router_set_announce_callback(
        router as u64,
        Arc::new(move |dest_hash: &[u8], display_name: Option<String>| {
            let guard = ANNOUNCE_CB.lock().unwrap();
            let (jvm, cb_ref) = match guard.as_ref() {
                Some(pair) => pair,
                None => return,
            };

            let mut env = match jvm.attach_current_thread() {
                Ok(env) => env,
                Err(_) => return,
            };

            let j_hash = match env.byte_array_from_slice(dest_hash) {
                Ok(h) => h,
                Err(_) => return,
            };

            let j_name = match &display_name {
                Some(name) => match env.new_string(name) {
                    Ok(s) => JObject::from(s),
                    Err(_) => JObject::null(),
                },
                None => JObject::null(),
            };

            let _ = env.call_method(
                cb_ref.as_obj(),
                "onAnnounce",
                "([BLjava/lang/String;)V",
                &[
                    JValue::Object(&j_hash),
                    JValue::Object(&j_name),
                ],
            );
        }),
    );

    ok_or_neg(result)
}

/// `RetichatBridge.nativeRouterAnnounce(router: Long, destHash: ByteArray): Int`
#[no_mangle]
pub extern "system" fn Java_com_retichat_app_bridge_RetichatBridge_nativeRouterAnnounce(
    env: JNIEnv,
    _class: JClass,
    router: jlong,
    dest_hash: JByteArray,
) -> jint {
    let h = jbytes_to_vec(&env, &dest_hash);
    ok_or_neg(lxmf::router_announce(router as u64, &h))
}

/// `RetichatBridge.nativeTransportPublishDestination(destHash: ByteArray, refreshSecs: Double): Int`
///
/// Opt the given destination hash into Transport's auto-announce daemon.
/// Transport will then re-announce automatically:
///   * once on every interface false→true online transition, and
///   * every `refresh_secs` seconds (pass 0.0 for up-edge-only).
#[no_mangle]
pub extern "system" fn Java_com_retichat_app_bridge_RetichatBridge_nativeTransportPublishDestination(
    env: JNIEnv,
    _class: JClass,
    dest_hash: JByteArray,
    refresh_secs: jni::sys::jdouble,
) -> jint {
    let h = jbytes_to_vec(&env, &dest_hash);
    rns::transport_publish_destination(&h, refresh_secs, None);
    0
}

/// `RetichatBridge.nativeTransportUnpublishDestination(destHash: ByteArray): Int`
#[no_mangle]
pub extern "system" fn Java_com_retichat_app_bridge_RetichatBridge_nativeTransportUnpublishDestination(
    env: JNIEnv,
    _class: JClass,
    dest_hash: JByteArray,
) -> jint {
    let h = jbytes_to_vec(&env, &dest_hash);
    rns::transport_unpublish_destination(&h);
    0
}

/// `RetichatBridge.nativeRouterWatchDestination(router: Long, destHash: ByteArray): Int`
#[no_mangle]
pub extern "system" fn Java_com_retichat_app_bridge_RetichatBridge_nativeRouterWatchDestination(
    env: JNIEnv,
    _class: JClass,
    router: jlong,
    dest_hash: JByteArray,
) -> jint {
    let h = jbytes_to_vec(&env, &dest_hash);
    ok_or_neg(lxmf::router_watch_destination(router as u64, &h))
}

/// `RetichatBridge.nativeRouterProcessOutbound(router: Long): Int`
#[no_mangle]
pub extern "system" fn Java_com_retichat_app_bridge_RetichatBridge_nativeRouterProcessOutbound(
    _env: JNIEnv,
    _class: JClass,
    router: jlong,
) -> jint {
    ok_or_neg(lxmf::router_process_outbound(router as u64))
}

/// `RetichatBridge.nativeRouterDestroy(router: Long): Int`
#[no_mangle]
pub extern "system" fn Java_com_retichat_app_bridge_RetichatBridge_nativeRouterDestroy(
    _env: JNIEnv,
    _class: JClass,
    router: jlong,
) -> jint {
    ok_or_neg(lxmf::router_destroy(router as u64))
}

// ---- Message ----

/// `RetichatBridge.nativeMessageCreate(destHash: ByteArray, srcHash: ByteArray, content: String, title: String, method: Int, identityHandle: Long): Long`
#[no_mangle]
pub extern "system" fn Java_com_retichat_app_bridge_RetichatBridge_nativeMessageCreate(
    mut env: JNIEnv,
    _class: JClass,
    dest_hash: JByteArray,
    src_hash: JByteArray,
    content: JString,
    title: JString,
    method: jint,
    identity_handle: jlong,
) -> jlong {
    let dh = jbytes_to_vec(&env, &dest_hash);
    let sh = jbytes_to_vec(&env, &src_hash);
    let c = jstring_to_string(&mut env, &content);
    let t = jstring_to_string(&mut env, &title);
    ok_or_zero(lxmf::message_create(&dh, &sh, &c, &t, method as u8, identity_handle as u64))
}

/// `RetichatBridge.nativeMessageAddAttachment(handle: Long, filename: String, data: ByteArray): Int`
#[no_mangle]
pub extern "system" fn Java_com_retichat_app_bridge_RetichatBridge_nativeMessageAddAttachment(
    mut env: JNIEnv,
    _class: JClass,
    handle: jlong,
    filename: JString,
    data: JByteArray,
) -> jint {
    let f = jstring_to_string(&mut env, &filename);
    let d = jbytes_to_vec(&env, &data);
    ok_or_neg(lxmf::message_add_attachment(handle as u64, &f, &d))
}

/// `RetichatBridge.nativeMessageAddFieldString(handle: Long, key: Int, value: String): Int`
#[no_mangle]
pub extern "system" fn Java_com_retichat_app_bridge_RetichatBridge_nativeMessageAddFieldString(
    mut env: JNIEnv,
    _class: JClass,
    handle: jlong,
    key: jint,
    value: JString,
) -> jint {
    let v = jstring_to_string(&mut env, &value);
    ok_or_neg(lxmf::message_add_field_string(handle as u64, key as u8, &v))
}

/// `RetichatBridge.nativeMessageAddFieldBool(handle: Long, key: Int, value: Boolean): Int`
#[no_mangle]
pub extern "system" fn Java_com_retichat_app_bridge_RetichatBridge_nativeMessageAddFieldBool(
    _env: JNIEnv,
    _class: JClass,
    handle: jlong,
    key: jint,
    value: jni::sys::jboolean,
) -> jint {
    ok_or_neg(lxmf::message_add_field_bool(handle as u64, key as u8, value != 0))
}

/// `RetichatBridge.nativeMessageSend(router: Long, msg: Long): Int`
#[no_mangle]
pub extern "system" fn Java_com_retichat_app_bridge_RetichatBridge_nativeMessageSend(
    _env: JNIEnv,
    _class: JClass,
    router: jlong,
    msg: jlong,
) -> jint {
    ok_or_neg(lxmf::message_send(router as u64, msg as u64))
}

/// `RetichatBridge.nativeMessageGetState(handle: Long): Int`
#[no_mangle]
pub extern "system" fn Java_com_retichat_app_bridge_RetichatBridge_nativeMessageGetState(
    _env: JNIEnv,
    _class: JClass,
    handle: jlong,
) -> jint {
    match lxmf::message_get_state(handle as u64) {
        Ok(s) => s as jint,
        Err(e) => {
            rns::set_error(e);
            -1
        }
    }
}

/// `RetichatBridge.nativeMessageGetProgress(handle: Long): Float`
#[no_mangle]
pub extern "system" fn Java_com_retichat_app_bridge_RetichatBridge_nativeMessageGetProgress(
    _env: JNIEnv,
    _class: JClass,
    handle: jlong,
) -> jfloat {
    match lxmf::message_get_progress(handle as u64) {
        Ok(p) => p,
        Err(e) => {
            rns::set_error(e);
            -1.0
        }
    }
}

/// `RetichatBridge.nativeMessageGetHash(handle: Long): ByteArray?`
#[no_mangle]
pub extern "system" fn Java_com_retichat_app_bridge_RetichatBridge_nativeMessageGetHash(
    env: JNIEnv,
    _class: JClass,
    handle: jlong,
) -> jbyteArray {
    match lxmf::message_get_hash(handle as u64) {
        Ok(h) => vec_to_jbytes(&env, &h),
        Err(e) => {
            rns::set_error(e);
            std::ptr::null_mut()
        }
    }
}

/// `RetichatBridge.nativeMessageDestroy(handle: Long): Int`
#[no_mangle]
pub extern "system" fn Java_com_retichat_app_bridge_RetichatBridge_nativeMessageDestroy(
    _env: JNIEnv,
    _class: JClass,
    handle: jlong,
) -> jint {
    ok_or_neg(lxmf::message_destroy(handle as u64))
}

// ---- Propagation ----

/// `RetichatBridge.nativeRouterSetPropagationNode(router: Long, destHash: ByteArray): Int`
#[no_mangle]
pub extern "system" fn Java_com_retichat_app_bridge_RetichatBridge_nativeRouterSetPropagationNode(
    env: JNIEnv,
    _class: JClass,
    router: jlong,
    dest_hash: JByteArray,
) -> jint {
    let h = jbytes_to_vec(&env, &dest_hash);
    ok_or_neg(lxmf::router_set_propagation_node(router as u64, &h))
}

/// `RetichatBridge.nativeRouterRequestMessages(router: Long, identity: Long): Int`
#[no_mangle]
pub extern "system" fn Java_com_retichat_app_bridge_RetichatBridge_nativeRouterRequestMessages(
    _env: JNIEnv,
    _class: JClass,
    router: jlong,
    identity: jlong,
) -> jint {
    ok_or_neg(lxmf::router_request_messages(router as u64, identity as u64))
}

/// `RetichatBridge.nativeRouterGetPropagationState(router: Long): Int`
#[no_mangle]
pub extern "system" fn Java_com_retichat_app_bridge_RetichatBridge_nativeRouterGetPropagationState(
    _env: JNIEnv,
    _class: JClass,
    router: jlong,
) -> jint {
    match lxmf::router_get_propagation_state(router as u64) {
        Ok(s) => s as jint,
        Err(e) => {
            rns::set_error(e);
            -1
        }
    }
}

/// `RetichatBridge.nativeRouterGetPropagationProgress(router: Long): Float`
#[no_mangle]
pub extern "system" fn Java_com_retichat_app_bridge_RetichatBridge_nativeRouterGetPropagationProgress(
    _env: JNIEnv,
    _class: JClass,
    router: jlong,
) -> jfloat {
    match lxmf::router_get_propagation_progress(router as u64) {
        Ok(p) => p as jfloat,
        Err(e) => {
            rns::set_error(e);
            -1.0
        }
    }
}

/// `RetichatBridge.nativeRouterCancelPropagation(router: Long): Int`
#[no_mangle]
pub extern "system" fn Java_com_retichat_app_bridge_RetichatBridge_nativeRouterCancelPropagation(
    _env: JNIEnv,
    _class: JClass,
    router: jlong,
) -> jint {
    ok_or_neg(lxmf::router_cancel_propagation(router as u64))
}

// ---------------------------------------------------------------------------
// Announce filtering
// ---------------------------------------------------------------------------

/// `RetichatBridge.nativeSetDropAnnounces(enabled: Boolean): Unit`
#[no_mangle]
pub extern "system" fn Java_com_retichat_app_bridge_RetichatBridge_nativeSetDropAnnounces(
    _env: JNIEnv,
    _class: JClass,
    enabled: jni::sys::jboolean,
) {
    rns::set_drop_announces(enabled != 0);
}

// ---------------------------------------------------------------------------
// Keepalive tuning
// ---------------------------------------------------------------------------

/// `RetichatBridge.nativeSetKeepaliveInterval(secs: Double): Int`
#[no_mangle]
pub extern "system" fn Java_com_retichat_app_bridge_RetichatBridge_nativeSetKeepaliveInterval(
    _env: JNIEnv,
    _class: JClass,
    secs: jni::sys::jdouble,
) -> jint {
    ok_or_neg(rns::set_keepalive_interval(secs))
}

// ---------------------------------------------------------------------------
// Identity sign (Ed25519)
// ---------------------------------------------------------------------------

/// `RetichatBridge.nativeIdentitySign(handle: Long, data: ByteArray): ByteArray?`
/// Returns 64-byte Ed25519 signature, or null on error.
#[no_mangle]
pub extern "system" fn Java_com_retichat_app_bridge_RetichatBridge_nativeIdentitySign(
    env: JNIEnv,
    _class: JClass,
    handle: jlong,
    data: JByteArray,
) -> jbyteArray {
    let d = jbytes_to_vec(&env, &data);
    match rns::identity_sign(handle as u64, &d) {
        Ok(sig) => vec_to_jbytes(&env, &sig),
        Err(e) => {
            rns::set_error(e);
            std::ptr::null_mut()
        }
    }
}

// ---------------------------------------------------------------------------
// Announce watchlist
// ---------------------------------------------------------------------------

/// `RetichatBridge.nativeWatchAnnounce(destHash: ByteArray): Unit`
#[no_mangle]
pub extern "system" fn Java_com_retichat_app_bridge_RetichatBridge_nativeWatchAnnounce(
    env: JNIEnv,
    _class: JClass,
    dest_hash: JByteArray,
) {
    let h = jbytes_to_vec(&env, &dest_hash);
    rns::watch_announce(h);
}

/// `RetichatBridge.nativeUnwatchAnnounce(destHash: ByteArray): Unit`
#[no_mangle]
pub extern "system" fn Java_com_retichat_app_bridge_RetichatBridge_nativeUnwatchAnnounce(
    env: JNIEnv,
    _class: JClass,
    dest_hash: JByteArray,
) {
    let h = jbytes_to_vec(&env, &dest_hash);
    rns::unwatch_announce(&h);
}

// ---------------------------------------------------------------------------
// Transport: persist path table
// ---------------------------------------------------------------------------

/// `RetichatBridge.nativeTransportSavePaths(): Int`
#[no_mangle]
pub extern "system" fn Java_com_retichat_app_bridge_RetichatBridge_nativeTransportSavePaths(
    _env: JNIEnv,
    _class: JClass,
) -> jint {
    Transport::save_path_table();
    0
}

// ---------------------------------------------------------------------------
// Raw packet send to hash (used by FCM token registration & channel SEND)
// ---------------------------------------------------------------------------

/// `RetichatBridge.nativePacketSendToHash(destHash, app, aspects, payload): Int`
/// `aspects` is a comma-separated string.
#[no_mangle]
pub extern "system" fn Java_com_retichat_app_bridge_RetichatBridge_nativePacketSendToHash(
    mut env: JNIEnv,
    _class: JClass,
    dest_hash: JByteArray,
    app_name: JString,
    aspects: JString,
    payload: JByteArray,
) -> jint {
    let hash = jbytes_to_vec(&env, &dest_hash);
    let app = jstring_to_string(&mut env, &app_name);
    let asp_str = jstring_to_string(&mut env, &aspects);
    let asp_vec: Vec<String> = asp_str
        .split(',')
        .map(|s| s.trim().to_string())
        .filter(|s| !s.is_empty())
        .collect();
    let data = jbytes_to_vec(&env, &payload);

    let dest_handle = match rns::destination_create_outbound_from_hash(&hash, &app, asp_vec) {
        Ok(h) => h,
        Err(e) => {
            rns::set_error(e);
            return -1;
        }
    };
    let pkt_handle = match rns::packet_create(dest_handle, &data, false) {
        Ok(h) => h,
        Err(e) => {
            rns::destroy_handle(dest_handle);
            rns::set_error(e);
            return -1;
        }
    };
    rns::destroy_handle(dest_handle);
    match rns::packet_send(pkt_handle) {
        Ok(_) => 0,
        Err(e) => {
            rns::set_error(e);
            -1
        }
    }
}

// ---------------------------------------------------------------------------
// Synchronous link request (blocks — must be called from a background thread)
// ---------------------------------------------------------------------------

/// `RetichatBridge.nativeLinkRequest(destHash, app, aspects, identity, path, payload, timeoutSecs): ByteArray?`
#[no_mangle]
pub extern "system" fn Java_com_retichat_app_bridge_RetichatBridge_nativeLinkRequest(
    mut env: JNIEnv,
    _class: JClass,
    dest_hash: JByteArray,
    app_name: JString,
    aspects: JString,
    identity_handle: jlong,
    path: JString,
    payload: JByteArray,
    timeout_secs: jni::sys::jdouble,
) -> jbyteArray {
    let hash = jbytes_to_vec(&env, &dest_hash);
    let app = jstring_to_string(&mut env, &app_name);
    let asp_str = jstring_to_string(&mut env, &aspects);
    let asp_vec: Vec<String> = asp_str
        .split(',')
        .map(|s| s.trim().to_string())
        .filter(|s| !s.is_empty())
        .collect();
    let p = jstring_to_string(&mut env, &path);
    let data = jbytes_to_vec(&env, &payload);

    match rns::link_request(&hash, &app, asp_vec, identity_handle as u64, &p, &data, timeout_secs) {
        Ok(response) => vec_to_jbytes(&env, &response),
        Err(e) => {
            rns::set_error(e);
            std::ptr::null_mut()
        }
    }
}

// ---------------------------------------------------------------------------
// RFed Delivery — inbound channel blob endpoint (JNI callback)
// ---------------------------------------------------------------------------
//
// Single global delivery state.  Callback is delivered to a Kotlin object
// implementing:
//   interface RfedBlobCallback { fun onBlob(blob: ByteArray) }

struct RfedDeliveryState {
    dest: Destination,
    _callback: GlobalRef,
}

static RFED_DELIVERY: Mutex<Option<RfedDeliveryState>> = Mutex::new(None);
static RFED_DELIVERY_CB: Mutex<Option<(JavaVM, GlobalRef)>> = Mutex::new(None);

/// `RetichatBridge.nativeRfedDeliveryStart(identityHandle: Long, callback: RfedBlobCallback): Int`
#[no_mangle]
pub extern "system" fn Java_com_retichat_app_bridge_RetichatBridge_nativeRfedDeliveryStart(
    mut env: JNIEnv,
    _class: JClass,
    identity_handle: jlong,
    callback: JObject,
) -> jint {
    let identity: Identity = match rns::get_handle(identity_handle as u64) {
        Some(id) => id,
        None => {
            rns::set_error("invalid identity handle".into());
            return -1;
        }
    };

    let jvm = match env.get_java_vm() {
        Ok(vm) => vm,
        Err(e) => {
            rns::set_error(format!("get_java_vm: {}", e));
            return -1;
        }
    };
    let global_ref = match env.new_global_ref(&callback) {
        Ok(r) => r,
        Err(e) => {
            rns::set_error(format!("new_global_ref: {}", e));
            return -1;
        }
    };
    let cb_for_storage = match env.new_global_ref(&callback) {
        Ok(r) => r,
        Err(e) => {
            rns::set_error(format!("new_global_ref(2): {}", e));
            return -1;
        }
    };

    *RFED_DELIVERY_CB.lock().unwrap() = Some((jvm, global_ref));

    let mut dest = match Destination::new_inbound(
        Some(identity),
        DestinationType::Single,
        "rfed".to_string(),
        vec!["delivery".to_string()],
    ) {
        Ok(d) => d,
        Err(e) => {
            rns::set_error(e);
            return -1;
        }
    };

    let packet_cb: Arc<dyn Fn(&[u8], &Packet) + Send + Sync> =
        Arc::new(move |data: &[u8], _pkt: &Packet| {
            let guard = RFED_DELIVERY_CB.lock().unwrap();
            let (jvm, cb_ref) = match guard.as_ref() {
                Some(p) => p,
                None => return,
            };
            let mut env = match jvm.attach_current_thread() {
                Ok(e) => e,
                Err(_) => return,
            };
            let j_blob = match env.byte_array_from_slice(data) {
                Ok(b) => b,
                Err(_) => return,
            };
            let _ = env.call_method(
                cb_ref.as_obj(),
                "onBlob",
                "([B)V",
                &[JValue::Object(&j_blob)],
            );
        });
    dest.set_packet_callback(Some(packet_cb));
    Transport::register_destination(dest.clone());

    *RFED_DELIVERY.lock().unwrap() = Some(RfedDeliveryState { dest, _callback: cb_for_storage });
    0
}

/// `RetichatBridge.nativeRfedDeliveryAnnounce(): Int`
#[no_mangle]
pub extern "system" fn Java_com_retichat_app_bridge_RetichatBridge_nativeRfedDeliveryAnnounce(
    _env: JNIEnv,
    _class: JClass,
) -> jint {
    let mut guard = RFED_DELIVERY.lock().unwrap();
    if let Some(ref mut state) = *guard {
        if let Err(e) = state.dest.announce(None, false, None, None, true) {
            rns::set_error(e);
            return -1;
        }
        return 0;
    }
    rns::set_error("rfed delivery not started".into());
    -1
}

/// `RetichatBridge.nativeRfedDeliveryStop(): Int`
#[no_mangle]
pub extern "system" fn Java_com_retichat_app_bridge_RetichatBridge_nativeRfedDeliveryStop(
    _env: JNIEnv,
    _class: JClass,
) -> jint {
    let mut guard = RFED_DELIVERY.lock().unwrap();
    if let Some(state) = guard.take() {
        Transport::deregister_destination(&state.dest.hash);
    }
    *RFED_DELIVERY_CB.lock().unwrap() = None;
    0
}

// ---------------------------------------------------------------------------
// Channel crypto / stamp / LXM pack-unpack
//
// Mirrors Retichat-ios/rust/retichat-ffi/src/lib.rs verbatim for wire
// compatibility.  See /memories/repo/retichat-rfed-channel-integration.md
// for the wire-format contract and historical regressions.
// ---------------------------------------------------------------------------

const LXMF_APP_NAME: &str = "lxmf";
const LXMF_DELIVERY_ASPECT: &str = "delivery";
const CHANNEL_IDENTITY_PRELUDE_MAGIC: &[u8; 4] = b"RTID";
const CHANNEL_IDENTITY_PRELUDE_LEN: usize = 4 + 64;

fn channel_private_key_bytes(name: &str) -> [u8; 64] {
    let seed: [u8; 32] = Sha256::digest(name.as_bytes()).into();
    let mut prv = [0u8; 64];
    prv[..32].copy_from_slice(&seed);
    prv[32..].copy_from_slice(&seed);
    prv
}

fn channel_identity(name: &str) -> Result<Identity, String> {
    let prv = channel_private_key_bytes(name);
    Identity::from_bytes(&prv)
}

fn channel_destination(name: &str) -> Result<Destination, String> {
    let id = channel_identity(name)?;
    Destination::new_outbound(
        Some(id),
        DestinationType::Single,
        LXMF_APP_NAME.to_string(),
        vec![LXMF_DELIVERY_ASPECT.to_string()],
    )
}

/// `RetichatBridge.nativeChannelEncrypt(name: String, plaintext: ByteArray): ByteArray?`
#[no_mangle]
pub extern "system" fn Java_com_retichat_app_bridge_RetichatBridge_nativeChannelEncrypt(
    mut env: JNIEnv,
    _class: JClass,
    name: JString,
    plaintext: JByteArray,
) -> jbyteArray {
    let n = jstring_to_string(&mut env, &name);
    let pt = jbytes_to_vec(&env, &plaintext);
    let identity = match channel_identity(&n) {
        Ok(id) => id,
        Err(e) => {
            rns::set_error(e);
            return std::ptr::null_mut();
        }
    };
    match identity.encrypt(&pt) {
        Ok(ct) => vec_to_jbytes(&env, &ct),
        Err(e) => {
            rns::set_error(e);
            std::ptr::null_mut()
        }
    }
}

/// `RetichatBridge.nativeChannelDecrypt(name: String, ciphertext: ByteArray): ByteArray?`
#[no_mangle]
pub extern "system" fn Java_com_retichat_app_bridge_RetichatBridge_nativeChannelDecrypt(
    mut env: JNIEnv,
    _class: JClass,
    name: JString,
    ciphertext: JByteArray,
) -> jbyteArray {
    let n = jstring_to_string(&mut env, &name);
    let ct = jbytes_to_vec(&env, &ciphertext);
    let mut identity = match channel_identity(&n) {
        Ok(id) => id,
        Err(e) => {
            rns::set_error(e);
            return std::ptr::null_mut();
        }
    };
    match identity.decrypt(&ct) {
        Ok(pt) => vec_to_jbytes(&env, &pt),
        Err(e) => {
            rns::set_error(e);
            std::ptr::null_mut()
        }
    }
}

/// `RetichatBridge.nativeComputeChannelStamp(payload: ByteArray, cost: Int): ByteArray?`
///
/// Returns 32-byte stamp, or null when cost == 0 (no stamp required) or PoW
/// fails.  See iOS retichat_compute_channel_stamp for the contract.
#[no_mangle]
pub extern "system" fn Java_com_retichat_app_bridge_RetichatBridge_nativeComputeChannelStamp(
    env: JNIEnv,
    _class: JClass,
    payload: JByteArray,
    cost: jint,
) -> jbyteArray {
    if cost <= 0 {
        return std::ptr::null_mut();
    }
    let cost_u = cost as u32;
    let data = jbytes_to_vec(&env, &payload);
    let transient_id = reticulum_rust::identity::full_hash(&data);
    let workblock = LXStamper::stamp_workblock(&transient_id, 16);
    let (stamp, value) = LXStamper::generate_stamp(&transient_id, cost_u, 16);
    if value < cost_u || !LXStamper::stamp_valid(&stamp, cost_u, &workblock) {
        rns::set_error(format!(
            "stamp PoW failed: required cost={} but achieved value={} (payload_len={})",
            cost_u, value, data.len()
        ));
        return std::ptr::null_mut();
    }
    vec_to_jbytes(&env, &stamp)
}

/// `RetichatBridge.nativeChannelLxmPack(name, senderHandle, content, title): ByteArray?`
///
/// Returns the same 8-byte-timestamp-prefixed wire buffer that iOS produces:
///   [ ts_ms_be(8) | channel_id_hash(16) | EC_encrypted(prelude || lxmf_tail) ]
/// Caller strips the first 8 bytes before sending; uses tsMs for local dedup.
#[no_mangle]
pub extern "system" fn Java_com_retichat_app_bridge_RetichatBridge_nativeChannelLxmPack(
    mut env: JNIEnv,
    _class: JClass,
    name: JString,
    sender_handle: jlong,
    content: JByteArray,
    title: JByteArray,
) -> jbyteArray {
    let n = jstring_to_string(&mut env, &name);
    if n.is_empty() {
        rns::set_error("channel name empty".into());
        return std::ptr::null_mut();
    }
    let content_v = jbytes_to_vec(&env, &content);
    let title_v = jbytes_to_vec(&env, &title);

    let sender_identity: Identity = match rns::get_handle::<Identity>(sender_handle as u64) {
        Some(id) => id,
        None => {
            rns::set_error("invalid sender identity handle".into());
            return std::ptr::null_mut();
        }
    };
    let sender_pub_bytes: Vec<u8> = match sender_identity.get_public_key() {
        Ok(b) => b,
        Err(e) => {
            rns::set_error(format!("sender pubkey: {}", e));
            return std::ptr::null_mut();
        }
    };
    if sender_pub_bytes.len() != 64 {
        rns::set_error(format!("sender pubkey wrong length: {}", sender_pub_bytes.len()));
        return std::ptr::null_mut();
    }

    let mut channel_dest = match channel_destination(&n) {
        Ok(d) => d,
        Err(e) => {
            rns::set_error(format!("channel dest: {}", e));
            return std::ptr::null_mut();
        }
    };
    let sender_dest = match Destination::new_outbound(
        Some(sender_identity),
        DestinationType::Single,
        LXMF_APP_NAME.to_string(),
        vec![LXMF_DELIVERY_ASPECT.to_string()],
    ) {
        Ok(d) => d,
        Err(e) => {
            rns::set_error(format!("sender dest: {}", e));
            return std::ptr::null_mut();
        }
    };

    let mut msg = match LXMessage::new(
        Some(channel_dest.clone()),
        Some(sender_dest),
        Some(content_v),
        Some(title_v),
        None,
        Some(LXMessage::PROPAGATED),
        None,
        None,
        None,
        false,
    ) {
        Ok(m) => m,
        Err(e) => {
            rns::set_error(format!("LXMessage::new: {}", e));
            return std::ptr::null_mut();
        }
    };
    if let Err(e) = msg.pack(false) {
        rns::set_error(format!("LXMessage::pack: {}", e));
        return std::ptr::null_mut();
    }
    let packed = match msg.packed.as_ref() {
        Some(p) => p,
        None => {
            rns::set_error("LXMessage missing packed".into());
            return std::ptr::null_mut();
        }
    };
    if packed.len() < LXMessage::DESTINATION_LENGTH {
        rns::set_error("packed too short".into());
        return std::ptr::null_mut();
    }
    let lxmf_tail = &packed[LXMessage::DESTINATION_LENGTH..];
    let mut prelude_plus_tail = Vec::with_capacity(CHANNEL_IDENTITY_PRELUDE_LEN + lxmf_tail.len());
    prelude_plus_tail.extend_from_slice(CHANNEL_IDENTITY_PRELUDE_MAGIC);
    prelude_plus_tail.extend_from_slice(&sender_pub_bytes);
    prelude_plus_tail.extend_from_slice(lxmf_tail);
    let pn_enc = match channel_dest.encrypt(&prelude_plus_tail) {
        Ok(d) => d,
        Err(e) => {
            rns::set_error(format!("channel encrypt: {}", e));
            return std::ptr::null_mut();
        }
    };

    let id_hash: Vec<u8> = match channel_identity(&n) {
        Ok(id) => match id.hash.clone() {
            Some(h) => h,
            None => {
                rns::set_error("channel identity has no hash".into());
                return std::ptr::null_mut();
            }
        },
        Err(e) => {
            rns::set_error(format!("channel identity: {}", e));
            return std::ptr::null_mut();
        }
    };
    if id_hash.len() != LXMessage::DESTINATION_LENGTH {
        rns::set_error("channel id hash wrong length".into());
        return std::ptr::null_mut();
    }

    let mut wire = Vec::with_capacity(8 + LXMessage::DESTINATION_LENGTH + pn_enc.len());
    let ts_ms: u64 = (msg.timestamp.unwrap_or(0.0) * 1000.0) as u64;
    wire.extend_from_slice(&ts_ms.to_be_bytes());
    wire.extend_from_slice(&id_hash);
    wire.extend_from_slice(&pn_enc);
    vec_to_jbytes(&env, &wire)
}

/// `RetichatBridge.nativeChannelLxmUnpack(name: String, lxmfData: ByteArray): ByteArray?`
///
/// Returns the same flat parsed-message buffer iOS produces:
///   [0..16]   source_hash
///   [16..24]  timestamp_ms_be
///   [24]      signature_validated (0/1)
///   [25]      unverified_reason (0=ok, 1=SOURCE_UNKNOWN, 2=SIGNATURE_INVALID)
///   [26..28]  title_len_be (u16)
///   [28..32]  content_len_be (u32)
///   [32..]    title bytes, then content bytes
#[no_mangle]
pub extern "system" fn Java_com_retichat_app_bridge_RetichatBridge_nativeChannelLxmUnpack(
    mut env: JNIEnv,
    _class: JClass,
    name: JString,
    lxmf_data: JByteArray,
) -> jbyteArray {
    let n = jstring_to_string(&mut env, &name);
    if n.is_empty() {
        rns::set_error("channel name empty".into());
        return std::ptr::null_mut();
    }
    let data = jbytes_to_vec(&env, &lxmf_data);
    if data.len() < LXMessage::DESTINATION_LENGTH + 32 {
        rns::set_error("lxmf_data too short".into());
        return std::ptr::null_mut();
    }

    let mut id = match channel_identity(&n) {
        Ok(id) => id,
        Err(e) => {
            rns::set_error(format!("channel identity: {}", e));
            return std::ptr::null_mut();
        }
    };
    let encrypted = &data[LXMessage::DESTINATION_LENGTH..];
    let decrypted = match id.decrypt(encrypted) {
        Ok(p) => p,
        Err(e) => {
            rns::set_error(format!("channel decrypt: {}", e));
            return std::ptr::null_mut();
        }
    };

    if decrypted.len() < CHANNEL_IDENTITY_PRELUDE_LEN
        || &decrypted[..4] != CHANNEL_IDENTITY_PRELUDE_MAGIC
    {
        rns::set_error("channel: missing SOURCE-IDENTITY PRELUDE".into());
        return std::ptr::null_mut();
    }
    let identity_pub = &decrypted[4..CHANNEL_IDENTITY_PRELUDE_LEN];
    let lxmf_tail = &decrypted[CHANNEL_IDENTITY_PRELUDE_LEN..];
    if lxmf_tail.len() < LXMessage::DESTINATION_LENGTH {
        rns::set_error("lxmf tail too short".into());
        return std::ptr::null_mut();
    }
    let claimed_source_hash = &lxmf_tail[..LXMessage::DESTINATION_LENGTH];
    if let Err(e) = Identity::remember_destination(claimed_source_hash, identity_pub, None) {
        rns::set_error(format!("remember_destination: {}", e));
        return std::ptr::null_mut();
    }

    let lxmf_dest = match channel_destination(&n) {
        Ok(d) => d.hash.clone(),
        Err(e) => {
            rns::set_error(format!("channel destination: {}", e));
            return std::ptr::null_mut();
        }
    };
    if lxmf_dest.len() != LXMessage::DESTINATION_LENGTH {
        rns::set_error("lxmf dest wrong length".into());
        return std::ptr::null_mut();
    }

    let mut full = Vec::with_capacity(lxmf_dest.len() + lxmf_tail.len());
    full.extend_from_slice(&lxmf_dest);
    full.extend_from_slice(lxmf_tail);

    let msg = match LXMessage::unpack_from_bytes(&full, Some(LXMessage::PROPAGATED)) {
        Ok(m) => m,
        Err(e) => {
            rns::set_error(format!("LXMessage::unpack: {}", e));
            return std::ptr::null_mut();
        }
    };

    let source_hash = msg.source_hash.clone();
    if source_hash.len() != LXMessage::DESTINATION_LENGTH {
        rns::set_error("source_hash wrong length".into());
        return std::ptr::null_mut();
    }
    let timestamp_ms: u64 = (msg.timestamp.unwrap_or(0.0) * 1000.0) as u64;
    let sig_ok: u8 = if msg.signature_validated { 1 } else { 0 };
    let reason: u8 = match msg.unverified_reason {
        Some(LXMessage::SOURCE_UNKNOWN) => 1,
        Some(LXMessage::SIGNATURE_INVALID) => 2,
        Some(other) => other,
        None => 0,
    };
    let title = msg.title.clone();
    let content = msg.content.clone();
    if title.len() > u16::MAX as usize {
        rns::set_error("title too large".into());
        return std::ptr::null_mut();
    }
    if content.len() > u32::MAX as usize {
        rns::set_error("content too large".into());
        return std::ptr::null_mut();
    }

    let mut out = Vec::with_capacity(32 + title.len() + content.len());
    out.extend_from_slice(&source_hash);
    out.extend_from_slice(&timestamp_ms.to_be_bytes());
    out.push(sig_ok);
    out.push(reason);
    out.extend_from_slice(&(title.len() as u16).to_be_bytes());
    out.extend_from_slice(&(content.len() as u32).to_be_bytes());
    out.extend_from_slice(&title);
    out.extend_from_slice(&content);
    vec_to_jbytes(&env, &out)
}

/// `RetichatBridge.nativeChannelHash16(name: String): ByteArray?`
///
/// Returns the 16-byte channel-identity hash derived from `name` — the
/// same value used as the lxmf_data prefix and as the routing key for
/// rfed channel subscribe/pull.  Mirrors `ChannelKeypair::hash` in the
/// Rust core (and `channelHash(name:)` in the iOS Swift client).
#[no_mangle]
pub extern "system" fn Java_com_retichat_app_bridge_RetichatBridge_nativeChannelHash16(
    mut env: JNIEnv,
    _class: JClass,
    name: JString,
) -> jbyteArray {
    let n = jstring_to_string(&mut env, &name);
    let dest = match channel_destination(&n) {
        Ok(d) => d,
        Err(e) => {
            rns::set_error(e);
            return std::ptr::null_mut();
        }
    };
    let hash = dest.hash.to_vec();
    vec_to_jbytes(&env, &hash)
}

// ---------------------------------------------------------------------------
// APP_LINK — persistent push-driven links
//
// Direct port of the iOS APP_LINK FFI surface (see
// Retichat-ios/Frameworks/RetichatFFI.xcframework/.../CRetichatFFI.h
// `lxmf_app_link_*` and Retichat-ios/Retichat/Services/LxmfClient.swift
// AppLink section).  All retries / readiness-waits are owned by the
// caller via the status callback — no polling, no app-level retries
// (DESIGN_PRINCIPLES.md §2, §3).
// ---------------------------------------------------------------------------

/// `RetichatBridge.nativeAppLinkOpen(router, destHash, app, aspectsCsv): Int`
///
/// `aspectsCsv` is `.`-separated (e.g. "delivery", "channel"); pass an
/// empty string for no aspects.
#[no_mangle]
pub extern "system" fn Java_com_retichat_app_bridge_RetichatBridge_nativeAppLinkOpen(
    mut env: JNIEnv,
    _class: JClass,
    router: jlong,
    dest_hash: JByteArray,
    app_name: JString,
    aspects_csv: JString,
) -> jint {
    let hash = jbytes_to_vec(&env, &dest_hash);
    let app = jstring_to_string(&mut env, &app_name);
    let asp_str = jstring_to_string(&mut env, &aspects_csv);
    let asp_owned: Vec<String> = if asp_str.is_empty() {
        Vec::new()
    } else {
        asp_str.split('.').map(|s| s.to_string()).collect()
    };
    let asp_refs: Vec<&str> = asp_owned.iter().map(|s| s.as_str()).collect();
    ok_or_neg(lxmf::router_app_link_open(router as u64, &hash, &app, &asp_refs))
}

/// `RetichatBridge.nativeAppLinkClose(router, destHash): Int`
#[no_mangle]
pub extern "system" fn Java_com_retichat_app_bridge_RetichatBridge_nativeAppLinkClose(
    env: JNIEnv,
    _class: JClass,
    router: jlong,
    dest_hash: JByteArray,
) -> jint {
    let hash = jbytes_to_vec(&env, &dest_hash);
    ok_or_neg(lxmf::router_app_link_close(router as u64, &hash))
}

/// `RetichatBridge.nativeAppLinkStatus(router, destHash): Int`
///
/// Returns 0..4 (NONE, PATH_REQUESTED, ESTABLISHING, ACTIVE, DISCONNECTED)
/// or -1 on parameter error.
#[no_mangle]
pub extern "system" fn Java_com_retichat_app_bridge_RetichatBridge_nativeAppLinkStatus(
    env: JNIEnv,
    _class: JClass,
    router: jlong,
    dest_hash: JByteArray,
) -> jint {
    let hash = jbytes_to_vec(&env, &dest_hash);
    match lxmf::router_app_link_status(router as u64, &hash) {
        Ok(s) => s as jint,
        Err(e) => {
            rns::set_error(e);
            -1
        }
    }
}

/// `RetichatBridge.nativeAppLinkRegisterReconnect(router, aspect): Int`
///
/// LXMF only auto-reconnects app-links that announce under `lxmf.delivery`.
/// Call once per extra aspect (e.g. "rfed.channel", "rfed.notify",
/// "rfed.delivery") at startup.
#[no_mangle]
pub extern "system" fn Java_com_retichat_app_bridge_RetichatBridge_nativeAppLinkRegisterReconnect(
    mut env: JNIEnv,
    _class: JClass,
    router: jlong,
    aspect: JString,
) -> jint {
    let asp = jstring_to_string(&mut env, &aspect);
    ok_or_neg(lxmf::router_register_app_link_reconnect_handler(
        router as u64,
        &asp,
    ))
}

/// `RetichatBridge.nativeAppLinkNetworkChanged(router): Int`
///
/// Triggers ONE fresh attempt for every registered app-link not
/// currently ACTIVE/ESTABLISHING.  Wire this to NetworkMonitor's
/// onAvailable callback — it is the only thing that retries an offline
/// destination (DESIGN_PRINCIPLES.md §1, §3).
#[no_mangle]
pub extern "system" fn Java_com_retichat_app_bridge_RetichatBridge_nativeAppLinkNetworkChanged(
    _env: JNIEnv,
    _class: JClass,
    router: jlong,
) -> jint {
    ok_or_neg(lxmf::router_app_link_network_changed(router as u64))
}

/// `RetichatBridge.nativeAppLinkRegisterStatusCallback(router, cb): Int`
///
/// `cb` is a Kotlin `AppLinkStatusCallback`:
/// ```kotlin
/// interface AppLinkStatusCallback { fun onStatus(destHash: ByteArray, status: Int) }
/// ```
/// Replaces any previously registered callback (last register wins on
/// the Kotlin side; the underlying Rust registry can hold multiple, but
/// we only need one process-wide fan-out).
#[no_mangle]
pub extern "system" fn Java_com_retichat_app_bridge_RetichatBridge_nativeAppLinkRegisterStatusCallback(
    env: JNIEnv,
    _class: JClass,
    router: jlong,
    callback: JObject,
) -> jint {
    let jvm = match env.get_java_vm() {
        Ok(vm) => vm,
        Err(e) => {
            rns::set_error(format!("Failed to get JavaVM: {}", e));
            return -1;
        }
    };
    let global_ref = match env.new_global_ref(&callback) {
        Ok(r) => r,
        Err(e) => {
            rns::set_error(format!("Failed to create global ref: {}", e));
            return -1;
        }
    };
    *APP_LINK_STATUS_CB.lock().unwrap() = Some((jvm, global_ref));

    let result = lxmf::router_register_app_link_status_callback(
        router as u64,
        Arc::new(
            |dest_hash: &[u8], status: u8, _link: Option<reticulum_rust::link::LinkHandle>| {
                let guard = APP_LINK_STATUS_CB.lock().unwrap();
                let (jvm, cb_ref) = match guard.as_ref() {
                    Some(pair) => pair,
                    None => return,
                };
                let mut env = match jvm.attach_current_thread() {
                    Ok(env) => env,
                    Err(_) => return,
                };
                let j_hash = match env.byte_array_from_slice(dest_hash) {
                    Ok(h) => h,
                    Err(_) => return,
                };
                let _ = env.call_method(
                    cb_ref.as_obj(),
                    "onStatus",
                    "([BI)V",
                    &[JValue::Object(&j_hash), JValue::Int(status as jint)],
                );
            },
        ),
    );
    ok_or_neg(result)
}

/// `RetichatBridge.nativeAppLinkRequestAsync(router, destHash, path,
///   payload, timeoutSecs, callback): Int`
///
/// Non-blocking variant.  Issues a request on the existing app-link
/// (which must have been opened with `nativeAppLinkOpen` and have
/// reached ACTIVE).  Fires `callback.onResult(status, bytes)` exactly
/// once when the response arrives, the request fails, or the timeout
/// elapses.
///
/// `status`: 0 = response (bytes set), 1 = timeout, 2 = failed,
///           3 = error (callback will NOT fire — check lastError).
///
/// Mirrors `lxmf_app_link_request_async` in LXMF-rust/src/cffi.rs and
/// the iOS Swift trampoline in LxmfClient.swift.
#[no_mangle]
pub extern "system" fn Java_com_retichat_app_bridge_RetichatBridge_nativeAppLinkRequestAsync(
    mut env: JNIEnv,
    _class: JClass,
    router: jlong,
    dest_hash: JByteArray,
    path: JString,
    payload: JByteArray,
    timeout_secs: jni::sys::jdouble,
    callback: JObject,
) -> jint {
    use std::sync::atomic::{AtomicBool, Ordering};
    use std::time::Duration;

    let hash = jbytes_to_vec(&env, &dest_hash);
    let path_str = jstring_to_string(&mut env, &path);
    let data = jbytes_to_vec(&env, &payload);

    // Snapshot the link handle for this destination (must already be ACTIVE).
    let link_handle = match lxmf::router_app_link_get_handle(router as u64, &hash) {
        Ok(Some(h)) => h,
        Ok(None) => {
            rns::set_error(
                "no app-link to destination — call nativeAppLinkOpen first".to_string(),
            );
            return -1;
        }
        Err(e) => {
            rns::set_error(e);
            return -1;
        }
    };
    if link_handle.status() != reticulum_rust::link::STATE_ACTIVE {
        rns::set_error(format!(
            "app-link not active (status={}) — wait for ACTIVE before requesting",
            link_handle.status()
        ));
        return -1;
    }

    // Per-call JavaVM + GlobalRef so multiple in-flight requests don't
    // clobber each other.  The single-fire latch ensures we drop the
    // global ref exactly once even if response/failed/timeout race.
    let jvm = match env.get_java_vm() {
        Ok(vm) => vm,
        Err(e) => {
            rns::set_error(format!("Failed to get JavaVM: {}", e));
            return -1;
        }
    };
    let global_ref = match env.new_global_ref(&callback) {
        Ok(r) => r,
        Err(e) => {
            rns::set_error(format!("Failed to create global ref: {}", e));
            return -1;
        }
    };

    // Wrap (jvm, ref) in Arc<Mutex<Option<...>>> so each terminal callback
    // can take() it: whichever runs first invokes onResult and drops the
    // ref; later callers see None and no-op.  This avoids leaking the
    // GlobalRef when response/failed/timeout race.
    let cb_state: Arc<Mutex<Option<(JavaVM, GlobalRef)>>> =
        Arc::new(Mutex::new(Some((jvm, global_ref))));
    let fired = Arc::new(AtomicBool::new(false));

    fn fire(state: &Arc<Mutex<Option<(JavaVM, GlobalRef)>>>, status: i32, bytes: Option<&[u8]>) {
        let taken = state.lock().unwrap().take();
        let (jvm, cb_ref) = match taken {
            Some(pair) => pair,
            None => return,
        };
        let mut env = match jvm.attach_current_thread() {
            Ok(env) => env,
            Err(_) => return,
        };
        let j_bytes = match bytes {
            Some(b) => match env.byte_array_from_slice(b) {
                Ok(arr) => JObject::from(arr),
                Err(_) => JObject::null(),
            },
            None => JObject::null(),
        };
        let _ = env.call_method(
            cb_ref.as_obj(),
            "onResult",
            "(I[B)V",
            &[JValue::Int(status), JValue::Object(&j_bytes)],
        );
    }

    let state_ok = cb_state.clone();
    let fired_ok = fired.clone();
    let response_cb: Arc<dyn Fn(reticulum_rust::link::RequestReceipt) + Send + Sync> =
        Arc::new(move |receipt: reticulum_rust::link::RequestReceipt| {
            if fired_ok.swap(true, Ordering::SeqCst) {
                return;
            }
            match receipt.response {
                Some(ref data) => fire(&state_ok, 0, Some(data)),
                None => fire(&state_ok, 2, None),
            }
        });

    let state_fail = cb_state.clone();
    let fired_fail = fired.clone();
    let failed_cb: Arc<dyn Fn(reticulum_rust::link::RequestReceipt) + Send + Sync> =
        Arc::new(move |_receipt| {
            if fired_fail.swap(true, Ordering::SeqCst) {
                return;
            }
            fire(&state_fail, 2, None);
        });

    // Off-load the synchronous link.request() onto a worker thread to
    // avoid priority inversion on a cooperative caller (mirrors the
    // pattern in LXMF-rust/src/cffi.rs::lxmf_app_link_request_async).
    // // NEVER REMOVE EVER — see DESIGN_PRINCIPLES.md §1
    let state_send_err = cb_state.clone();
    let fired_send_err = fired.clone();
    let link_for_send = link_handle.clone();
    std::thread::spawn(move || {
        if let Err(e) = link_for_send.request(
            path_str,
            data,
            Some(response_cb),
            Some(failed_cb),
            None,
        ) {
            if !fired_send_err.swap(true, Ordering::SeqCst) {
                fire(&state_send_err, 2, None);
            }
            rns::set_error(format!("link.request failed: {:?}", e));
        }
    });

    // Detached timeout watcher.
    let state_to = cb_state.clone();
    let fired_to = fired.clone();
    let to_secs = timeout_secs.max(0.0);
    std::thread::spawn(move || {
        std::thread::sleep(Duration::from_secs_f64(to_secs));
        if !fired_to.swap(true, Ordering::SeqCst) {
            fire(&state_to, 1, None);
        }
    });

    0
}
