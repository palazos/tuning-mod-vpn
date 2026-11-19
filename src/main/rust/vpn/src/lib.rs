mod engine;
mod identity;
mod selftest;
mod session;
mod stun;
#[cfg(unix)]
mod tun;
mod upnp;

use jni::objects::{JByteArray, JClass, JString};
use jni::sys::{jboolean, jbyteArray, jint, jlong, jstring, JNI_FALSE, JNI_TRUE};
use jni::JNIEnv;
use std::net::{Ipv4Addr, SocketAddr};
use std::panic::{catch_unwind, AssertUnwindSafe};
use std::ptr::null_mut;
use std::sync::{Arc, Mutex};

fn to_jstring(env: &mut JNIEnv, value: &str) -> jstring {
    env.new_string(value)
        .map(|s| s.into_raw())
        .unwrap_or(null_mut())
}

fn read_array<const N: usize>(env: &JNIEnv, array: &JByteArray) -> Option<[u8; N]> {
    env.convert_byte_array(array).ok()?.try_into().ok()
}

fn read_bytes(env: &JNIEnv, array: &JByteArray) -> Option<Vec<u8>> {
    env.convert_byte_array(array).ok()
}

fn to_jbytes(env: &JNIEnv, data: &[u8]) -> jbyteArray {
    env.byte_array_from_slice(data)
        .map(|a| a.into_raw())
        .unwrap_or(null_mut())
}

#[no_mangle]
pub extern "system" fn Java_com_winlator_cmod_vpn_core_VpnNative_nativeVersion<'l>(
    mut env: JNIEnv<'l>,
    _class: JClass<'l>,
) -> jstring {
    to_jstring(&mut env, env!("CARGO_PKG_VERSION"))
}

#[no_mangle]
pub extern "system" fn Java_com_winlator_cmod_vpn_core_VpnNative_nativeSelfTest<'l>(
    mut env: JNIEnv<'l>,
    _class: JClass<'l>,
) -> jstring {
    let message = match catch_unwind(selftest::run) {
        Ok(Ok(summary)) => format!("OK: {summary}"),
        Ok(Err(e)) => format!("FAIL: {e}"),
        Err(_) => "FAIL: panic".to_string(),
    };
    to_jstring(&mut env, &message)
}

#[no_mangle]
pub extern "system" fn Java_com_winlator_cmod_vpn_core_VpnNative_nativeGenerateSeed<'l>(
    env: JNIEnv<'l>,
    _class: JClass<'l>,
) -> jbyteArray {
    to_jbytes(&env, &identity::generate_seed())
}

#[no_mangle]
pub extern "system" fn Java_com_winlator_cmod_vpn_core_VpnNative_nativePublicKey<'l>(
    env: JNIEnv<'l>,
    _class: JClass<'l>,
    seed: JByteArray<'l>,
) -> jbyteArray {
    match read_array::<32>(&env, &seed) {
        Some(seed) => to_jbytes(&env, &identity::public_key(&seed)),
        None => null_mut(),
    }
}

#[no_mangle]
pub extern "system" fn Java_com_winlator_cmod_vpn_core_VpnNative_nativeSign<'l>(
    env: JNIEnv<'l>,
    _class: JClass<'l>,
    seed: JByteArray<'l>,
    message: JByteArray<'l>,
) -> jbyteArray {
    match (read_array::<32>(&env, &seed), read_bytes(&env, &message)) {
        (Some(seed), Some(message)) => to_jbytes(&env, &identity::sign(&seed, &message)),
        _ => null_mut(),
    }
}

#[no_mangle]
pub extern "system" fn Java_com_winlator_cmod_vpn_core_VpnNative_nativeVerify<'l>(
    env: JNIEnv<'l>,
    _class: JClass<'l>,
    public_key: JByteArray<'l>,
    message: JByteArray<'l>,
    signature: JByteArray<'l>,
) -> jboolean {
    let valid = match (
        read_array::<32>(&env, &public_key),
        read_bytes(&env, &message),
        read_array::<64>(&env, &signature),
    ) {
        (Some(key), Some(message), Some(signature)) => identity::verify(&key, &message, &signature),
        _ => false,
    };
    if valid { JNI_TRUE } else { JNI_FALSE }
}

#[no_mangle]
pub extern "system" fn Java_com_winlator_cmod_vpn_core_VpnNative_nativeRoomId<'l>(
    mut env: JNIEnv<'l>,
    _class: JClass<'l>,
    public_key: JByteArray<'l>,
) -> jstring {
    match read_array::<32>(&env, &public_key) {
        Some(key) => to_jstring(&mut env, &identity::room_id(&key)),
        None => null_mut(),
    }
}

fn session_and_payload(session: [u8; session::SESSION_LEN], payload: &[u8]) -> Vec<u8> {
    let mut out = session.to_vec();
    out.extend_from_slice(payload);
    out
}

/// Returns session secret (32) || packet.
#[no_mangle]
pub extern "system" fn Java_com_winlator_cmod_vpn_core_VpnNative_nativeSealJoin<'l>(
    env: JNIEnv<'l>,
    _class: JClass<'l>,
    host_public: JByteArray<'l>,
    plaintext: JByteArray<'l>,
) -> jbyteArray {
    match (read_array::<32>(&env, &host_public), read_bytes(&env, &plaintext)) {
        (Some(key), Some(plaintext)) => match session::seal_join(&key, &plaintext) {
            Some((session, packet)) => to_jbytes(&env, &session_and_payload(session, &packet)),
            None => null_mut(),
        },
        _ => null_mut(),
    }
}

/// Returns session secret (32) || plaintext.
#[no_mangle]
pub extern "system" fn Java_com_winlator_cmod_vpn_core_VpnNative_nativeOpenJoin<'l>(
    env: JNIEnv<'l>,
    _class: JClass<'l>,
    seed: JByteArray<'l>,
    packet: JByteArray<'l>,
) -> jbyteArray {
    match (read_array::<32>(&env, &seed), read_bytes(&env, &packet)) {
        (Some(seed), Some(packet)) => match session::open_join(&seed, &packet) {
            Some((session, plaintext)) => to_jbytes(&env, &session_and_payload(session, &plaintext)),
            None => null_mut(),
        },
        _ => null_mut(),
    }
}

#[no_mangle]
pub extern "system" fn Java_com_winlator_cmod_vpn_core_VpnNative_nativeEncrypt<'l>(
    env: JNIEnv<'l>,
    _class: JClass<'l>,
    session: JByteArray<'l>,
    direction: jint,
    plaintext: JByteArray<'l>,
) -> jbyteArray {
    match (read_array::<32>(&env, &session), read_bytes(&env, &plaintext)) {
        (Some(session), Some(plaintext)) => match session::encrypt(&session, direction as u8, &plaintext) {
            Some(packet) => to_jbytes(&env, &packet),
            None => null_mut(),
        },
        _ => null_mut(),
    }
}

#[no_mangle]
pub extern "system" fn Java_com_winlator_cmod_vpn_core_VpnNative_nativeDecrypt<'l>(
    env: JNIEnv<'l>,
    _class: JClass<'l>,
    session: JByteArray<'l>,
    direction: jint,
    packet: JByteArray<'l>,
) -> jbyteArray {
    match (read_array::<32>(&env, &session), read_bytes(&env, &packet)) {
        (Some(session), Some(packet)) => match session::decrypt(&session, direction as u8, &packet) {
            Some(plaintext) => to_jbytes(&env, &plaintext),
            None => null_mut(),
        },
        _ => null_mut(),
    }
}

// ---- Room tunnel engine ----

static ENGINE: Mutex<Option<Arc<engine::Engine>>> = Mutex::new(None);

fn current_engine() -> Option<Arc<engine::Engine>> {
    ENGINE.lock().unwrap_or_else(|e| e.into_inner()).clone()
}

fn read_string(env: &mut JNIEnv, value: &JString) -> Option<String> {
    env.get_string(value).ok().map(String::from)
}

fn to_hex(bytes: &[u8]) -> String {
    bytes.iter().map(|b| format!("{b:02x}")).collect()
}

fn from_hex_32(text: &str) -> Option<[u8; 32]> {
    if text.len() != 64 || !text.is_ascii() {
        return None;
    }
    let mut out = [0u8; 32];
    for (i, byte) in out.iter_mut().enumerate() {
        *byte = u8::from_str_radix(&text[i * 2..i * 2 + 2], 16).ok()?;
    }
    Some(out)
}

const MAX_CANDIDATES: usize = 16;

// Candidates come from other players, so keep only addresses that make sense to probe.
fn parse_candidates(text: &str) -> Vec<SocketAddr> {
    text.split(',')
        .filter_map(|item| item.trim().parse::<SocketAddr>().ok())
        .filter(|addr| match addr {
            SocketAddr::V4(v4) => {
                let ip = v4.ip();
                v4.port() != 0
                    && !ip.is_unspecified()
                    && !ip.is_loopback()
                    && !ip.is_multicast()
                    && !ip.is_broadcast()
            }
            SocketAddr::V6(_) => false,
        })
        .take(MAX_CANDIDATES)
        .collect()
}

fn describe_opened(public: &[u8; 32], info: &engine::Opened, bound: bool, slot: u8) -> String {
    let nat = match info.symmetric {
        Some(true) => "symmetric",
        Some(false) => "cone",
        None => "unknown",
    };
    let joined = |addrs: &[SocketAddr]| addrs.iter().map(|addr| addr.to_string()).collect::<Vec<_>>().join(",");
    format!(
        "pub={};port={};mapped={};nat={};forwarded={};bound={};probes={};predict={};slot={}",
        to_hex(public),
        info.port,
        info.mapped.map(|addr| addr.to_string()).unwrap_or_default(),
        nat,
        info.forwarded.map(|addr| addr.to_string()).unwrap_or_default(),
        if bound { 1 } else { 0 },
        joined(&info.probes),
        joined(&info.predict),
        slot
    )
}

/// "pub=<hex>;port=<n>;mapped=<ip:port or empty>;nat=<cone|symmetric|unknown>;forwarded=<ip:port or empty>;bound=<0|1>;probes=<ip:port,...>;predict=<ip:port,...>;slot=<n>"
#[no_mangle]
pub extern "system" fn Java_com_winlator_cmod_vpn_core_VpnNative_nativeEngineOpen<'l>(
    mut env: JNIEnv<'l>,
    _class: JClass<'l>,
    gateway: JString<'l>,
    network_handle: jlong,
) -> jstring {
    let gateway = read_string(&mut env, &gateway).and_then(|s| s.parse::<Ipv4Addr>().ok());
    let network_handle = u64::try_from(network_handle).unwrap_or(0);
    let opened = catch_unwind(AssertUnwindSafe(|| {
        if let Some(old) = ENGINE.lock().unwrap_or_else(|e| e.into_inner()).take() {
            old.close();
        }
        let engine = engine::Engine::open(gateway, network_handle)?;
        let key = engine.public_key();
        let text = describe_opened(&key, engine.opened(), engine.network_bound(), 0);
        *ENGINE.lock().unwrap_or_else(|e| e.into_inner()) = Some(Arc::new(engine));
        Ok::<String, String>(text)
    }));
    match opened {
        Ok(Ok(text)) => to_jstring(&mut env, &text),
        _ => null_mut(),
    }
}

#[no_mangle]
pub extern "system" fn Java_com_winlator_cmod_vpn_core_VpnNative_nativeEngineOpenDoor<'l>(
    mut env: JNIEnv<'l>,
    _class: JClass<'l>,
    gateway: JString<'l>,
    network_handle: jlong,
) -> jstring {
    let gateway = read_string(&mut env, &gateway).and_then(|s| s.parse::<Ipv4Addr>().ok());
    let network_handle = u64::try_from(network_handle).unwrap_or(0);
    let opened = catch_unwind(AssertUnwindSafe(|| {
        let engine = current_engine().ok_or_else(|| "closed".to_string())?;
        let (slot, info, bound) = engine.open_door(gateway, network_handle)?;
        let key = engine.public_key();
        Ok::<String, String>(describe_opened(&key, &info, bound, slot))
    }));
    match opened {
        Ok(Ok(text)) => to_jstring(&mut env, &text),
        _ => null_mut(),
    }
}

#[no_mangle]
pub extern "system" fn Java_com_winlator_cmod_vpn_core_VpnNative_nativeEngineCloseDoor<'l>(
    _env: JNIEnv<'l>,
    _class: JClass<'l>,
    slot: jint,
) {
    if let Some(engine) = current_engine() {
        engine.close_door(slot as u8);
    }
}

#[no_mangle]
pub extern "system" fn Java_com_winlator_cmod_vpn_core_VpnNative_nativeEngineSocket<'l>(
    _env: JNIEnv<'l>,
    _class: JClass<'l>,
) -> jint {
    current_engine().map(|engine| engine.socket_fd()).unwrap_or(-1)
}

#[no_mangle]
pub extern "system" fn Java_com_winlator_cmod_vpn_core_VpnNative_nativeEngineStart<'l>(
    mut env: JNIEnv<'l>,
    _class: JClass<'l>,
    fd: jint,
    is_host: jboolean,
    local: JString<'l>,
) -> jboolean {
    let local = read_string(&mut env, &local).and_then(|s| s.parse::<Ipv4Addr>().ok());
    let (Some(engine), Some(local)) = (current_engine(), local) else { return JNI_FALSE };
    #[cfg(unix)]
    let ok = engine.attach_tun(fd, is_host != 0, local);
    #[cfg(not(unix))]
    let ok = {
        let _ = (fd, is_host, local, &engine);
        false
    };
    if ok { JNI_TRUE } else { JNI_FALSE }
}

#[no_mangle]
pub extern "system" fn Java_com_winlator_cmod_vpn_core_VpnNative_nativeEngineDetach<'l>(
    _env: JNIEnv<'l>,
    _class: JClass<'l>,
) {
    #[cfg(unix)]
    if let Some(engine) = current_engine() {
        engine.detach_tun();
    }
}

#[no_mangle]
pub extern "system" fn Java_com_winlator_cmod_vpn_core_VpnNative_nativeEngineAddPeer<'l>(
    mut env: JNIEnv<'l>,
    _class: JClass<'l>,
    public_key: JString<'l>,
    address: JString<'l>,
    candidates: JString<'l>,
    initiator: jboolean,
    slot: jint,
) -> jboolean {
    let key = read_string(&mut env, &public_key).and_then(|s| from_hex_32(&s));
    let address = read_string(&mut env, &address).and_then(|s| s.parse::<Ipv4Addr>().ok());
    let candidates = read_string(&mut env, &candidates).map(|s| parse_candidates(&s)).unwrap_or_default();
    match (current_engine(), key, address) {
        (Some(engine), Some(key), Some(address)) => {
            engine.add_peer(key, address, candidates, initiator != 0, slot as u8);
            JNI_TRUE
        }
        _ => JNI_FALSE,
    }
}

#[no_mangle]
pub extern "system" fn Java_com_winlator_cmod_vpn_core_VpnNative_nativeEngineRemovePeer<'l>(
    mut env: JNIEnv<'l>,
    _class: JClass<'l>,
    address: JString<'l>,
) {
    let address = read_string(&mut env, &address).and_then(|s| s.parse::<Ipv4Addr>().ok());
    if let (Some(engine), Some(address)) = (current_engine(), address) {
        engine.remove_peer(address);
    }
}

/// One line per peer: "<address>,<up|connecting>,<endpoint>,<tx>,<rx>,<round trip ms or -1>,<lan|internet>"
#[no_mangle]
pub extern "system" fn Java_com_winlator_cmod_vpn_core_VpnNative_nativeEngineStatus<'l>(
    mut env: JNIEnv<'l>,
    _class: JClass<'l>,
) -> jstring {
    let Some(engine) = current_engine() else { return null_mut() };
    let text = engine
        .status()
        .iter()
        .map(|p| {
            format!(
                "{},{},{},{},{},{},{}",
                p.address,
                if p.established { "up" } else { "connecting" },
                p.endpoint.map(|e| e.to_string()).unwrap_or_default(),
                p.tx,
                p.rx,
                p.rtt_us.map(|us| (us / 1000).to_string()).unwrap_or_else(|| "-1".to_string()),
                match p.endpoint {
                    Some(SocketAddr::V4(v4)) if v4.ip().is_private() || v4.ip().is_link_local() => "lan",
                    Some(_) => "internet",
                    None => "",
                }
            )
        })
        .collect::<Vec<_>>()
        .join("\n");
    to_jstring(&mut env, &text)
}

#[no_mangle]
pub extern "system" fn Java_com_winlator_cmod_vpn_core_VpnNative_nativeEngineClose<'l>(
    _env: JNIEnv<'l>,
    _class: JClass<'l>,
) {
    let engine = ENGINE.lock().unwrap_or_else(|e| e.into_inner()).take();
    if let Some(engine) = engine {
        engine.close();
    }
}
