"""What an Aggregate Device is made of, from Core Audio (ctypes, no extra packages).

The Start page names each input channel ("0 = MacBook Pro Microphone, 1-2 = BlackHole 2ch") and
notices an aggregate whose headset is unplugged (only BlackHole left), instead of guessing from
channel counts. An aggregate's input channels are its sub-devices' channels, in sub-device order.
"""
import ctypes
import ctypes.util
import logging
import struct

log = logging.getLogger("earshot_capture")


def _fourcc(s: str) -> int:
    return struct.unpack(">I", s.encode())[0]


class _Address(ctypes.Structure):
    _fields_ = [("selector", ctypes.c_uint32), ("scope", ctypes.c_uint32), ("element", ctypes.c_uint32)]


_SYSTEM = 1
_GLOBAL = _fourcc("glob")
_INPUT = _fourcc("inpt")
_UTF8 = 0x08000100

try:
    _ca = ctypes.cdll.LoadLibrary(ctypes.util.find_library("CoreAudio"))
    _cf = ctypes.cdll.LoadLibrary(ctypes.util.find_library("CoreFoundation"))
    _ca.AudioObjectGetPropertyDataSize.argtypes = [ctypes.c_uint32, ctypes.POINTER(_Address), ctypes.c_uint32, ctypes.c_void_p,
                                                   ctypes.POINTER(ctypes.c_uint32)]
    _ca.AudioObjectGetPropertyData.argtypes = [ctypes.c_uint32, ctypes.POINTER(_Address), ctypes.c_uint32, ctypes.c_void_p,
                                               ctypes.POINTER(ctypes.c_uint32), ctypes.c_void_p]
    _cf.CFStringGetCString.argtypes = [ctypes.c_void_p, ctypes.c_char_p, ctypes.c_long, ctypes.c_uint32]
    _cf.CFRelease.argtypes = [ctypes.c_void_p]
except (OSError, TypeError, AttributeError):  # not macOS
    _ca = None


def _get(obj: int, selector: str, scope: int = _GLOBAL) -> bytes | None:
    addr = _Address(_fourcc(selector), scope, 0)
    size = ctypes.c_uint32(0)
    if _ca.AudioObjectGetPropertyDataSize(obj, ctypes.byref(addr), 0, None, ctypes.byref(size)) or not size.value:
        return None
    buf = ctypes.create_string_buffer(size.value)
    if _ca.AudioObjectGetPropertyData(obj, ctypes.byref(addr), 0, None, ctypes.byref(size), buf):
        return None
    return buf.raw[: size.value]


def _ids(raw: bytes | None) -> list[int]:
    return list(struct.unpack(f"<{len(raw) // 4}I", raw)) if raw else []


def _name(dev: int) -> str:
    raw = _get(dev, "lnam")
    if not raw:
        return ""
    ref = ctypes.c_void_p(struct.unpack("<Q", raw[:8])[0])
    out = ctypes.create_string_buffer(512)
    ok = _cf.CFStringGetCString(ref, out, 512, _UTF8)
    _cf.CFRelease(ref)
    return out.value.decode("utf-8", "replace") if ok else ""


def _input_channels(dev: int) -> int:
    # AudioBufferList: UInt32 count, then 16-byte AudioBuffers (UInt32 channels, UInt32 bytes, void*) from offset 8.
    raw = _get(dev, "slay", _INPUT)
    if not raw or len(raw) < 4:
        return 0
    n = struct.unpack_from("<I", raw, 0)[0]
    return sum(struct.unpack_from("<I", raw, 8 + 16 * i)[0] for i in range(n) if 8 + 16 * i + 4 <= len(raw))


def parts() -> dict[str, list[dict]]:
    """Aggregate device name -> its input sub-devices in channel order: [{name, first, channels}]."""
    if _ca is None:
        return {}
    out: dict[str, list[dict]] = {}
    try:
        for dev in _ids(_get(_SYSTEM, "dev#")):
            subs = _ids(_get(dev, "agrp"))  # active sub-devices: only aggregates have any
            if not subs:
                continue
            first, items = 0, []
            for sub in subs:
                ch = _input_channels(sub)
                if ch:
                    items.append({"name": _name(sub), "first": first, "channels": ch})
                    first += ch
            out[_name(dev)] = items
    except Exception as e:  # never break the device list over this
        log.warning("event=aggregate_parts_failed reason=%s", e)
    return out
