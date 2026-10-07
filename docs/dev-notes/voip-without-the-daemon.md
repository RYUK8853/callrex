# VoIP without the daemon — the blocker, and the one idea that might get past it

Researched 2026-07-27 against AOSP source, to decide whether "debug bypass" (a debugging switch needed
once at boot and never again) can include **app calls**, or only carrier calls.

## The answer as things stand: no

**A dynamic `AudioPolicy` is torn down when the process that registered it dies.** Not a leak, not a
bug — the designed contract.

```java
// frameworks/base AudioService.java:13389
pcb.asBinder().linkToDeath(app, 0);
// AudioPolicyProxy.binderDied() → release() → registerPolicyMixes(mMixes, false)
```

```cpp
// frameworks/av AudioPolicyManager.cpp:4249  unregisterPolicyMixes()
mPolicyMixes.unregisterMix(mix);
setDeviceConnectionStateInt(AUDIO_DEVICE_IN_REMOTE_SUBMIX,  UNAVAILABLE, address, …);
setDeviceConnectionStateInt(AUDIO_DEVICE_OUT_REMOTE_SUBMIX, UNAVAILABLE, address, …);
rSubmixModule->removeOutputProfile(address);
rSubmixModule->removeInputProfile(address);
```

So the far-party mix vanishes the moment the daemon dies, and the sink goes silent no matter who holds
its `AudioRecord`.

### Why this is structurally different from Track A

| | Implementation lives in | Consequence |
|---|---|---|
| `IAudioRecord` (Track A) | **audioserver** — persistent | Handing the proxy to the app just adds a ref. Nobody cares who holds it. |
| `IAudioPolicyCallback` (VoIP) | **the registering process** | The thing whose death matters is *inside* the process we want to kill. |

There is no AOSP API to transfer or re-parent a policy — checked `AudioService`, `AudioPolicy`,
`AudioManager`. The 1:1 binding to the registering process is by construction.

### What is NOT the blocker

- **The sink `AudioRecord`** — an ordinary record tagged `REMOTE_SUBMIX`; its binder lives in
  audioserver like any other, so the existing extraction would work unchanged.
- **The near-party MIC record** — ordinary in every respect; handoff behaves exactly like `VOICE_CALL`.

Both would hand off fine. Handing them off is necessary but useless while the mix dies first.

## The one idea that might get past it — UNTESTED

Two facts, both read from source, that are separable:

1. `isPolicyRegisterAllowed()` checks `Binder.getCallingUid()` — **who performs the registration**.
2. `linkToDeath` attaches to the `mPolicyCb` **object**, a plain private field on `AudioPolicy`
   (`AudioPolicy.java:1166`), holding an `IAudioPolicyCallback.Stub`.

So, hypothetically: have the **app** host a binder implementing that callback surface, substitute it
into the `AudioPolicy` before the **daemon** registers it. The registration still runs with the
daemon's uid — permissions satisfied — but the death that tears the mix down becomes the **app's**,
not the daemon's.

If it works, VoIP capture could be armed once and survive the daemon indefinitely, exactly as Track A
does for carrier calls.

**Why it is a separate research track, not an extension:** it needs reflection on a framework field
(hidden-API policy may block it) and a hand-rolled `onTransact` for an undocumented AIDL whose method
order is assumed — the same fragility already flagged for `HeldRecordControl`, but writing a *server*
rather than issuing two calls. Nothing in the codebase attempts it. It needs its own probe, and the
probe should answer one question first: **does the mix survive when the daemon is killed?** Everything
else is wasted effort if it does not.

## Incidental finding — the input-slot question

The far-party sink lives on the dynamically created **`r_submix`** HwModule, *not* the primary module
that hosts Telephony Rx and the built-in mics. Eviction and `maxOpenCount` are scoped per-profile
within a module, so **a held (stopped) `VOICE_CALL` track cannot affect the VoIP far-party sink.**

It does share the primary module with VoIP's near-party `MIC` capture: a held `VOICE_CALL` plus an
active VoIP `MIC` is 2 of that profile's 2 slots. Fine for one call at a time, with no headroom for a
third input client — consistent with the eviction risk already noted for `VOICE_CALL`'s priority 0.

## Consequence for the roadmap

**Debug bypass is achievable for carrier calls today and not for VoIP.** Anyone recording app calls
keeps a debugging switch on. That is a real limit to state plainly rather than imply away — and the
callback-ownership idea above is the only known route to lifting it.
