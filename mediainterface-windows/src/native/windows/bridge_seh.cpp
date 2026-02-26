#include "bridge_shared.h"
#include <Windows.h>

// ---------------------------------------------------------------------------
// Dedicated Win32 thread for the cold-boot RequestAsync() call.
//
// On the first launch after a reboot, the WinRT activation broker may not
// have finished its one-time factory initialisation for the SMTC reader API.
// RequestAsync().get() can fault with 0xC0000005 inside COM's proxy code.
//
// Running on a plain CreateThread thread (not a JVM-managed thread) means
// there are no JVM exception-handling frames around our code, so a standard
// __try/__except reliably intercepts the AV. Once the factory has warmed up
// successfully once, request_manager_safe switches to direct calls with no
// thread-creation overhead.
// ---------------------------------------------------------------------------

// Context shared between the caller and the worker thread.
// Written by exactly one party before being read by the other;
// the done_event provides the happens-before edge.
struct RequestCtx {
    GlobalSystemMediaTransportControlsSessionManager result{nullptr};
    HANDLE done_event = nullptr;
    bool success = false;
};

// Inner helper: owns the WinRT C++ objects (and therefore dtors).
// Marked noinline so the optimiser cannot merge it with the outer __try
// function — merging would put C++ dtors in scope alongside __try, which
// triggers MSVC error C2712.
__declspec(noinline)
static void do_request(RequestCtx* ctx) {
    ctx->result  = GlobalSystemMediaTransportControlsSessionManager::RequestAsync().get();
    ctx->success = true;
}

// Worker thread: no local C++ dtors in scope, so __try/__except is valid.
static DWORD WINAPI request_thread(LPVOID param) noexcept {
    auto* ctx = static_cast<RequestCtx*>(param);
    // No explicit CoInitializeEx needed: once the process MTA is initialised
    // (by nativeInit), threads that call WinRT without their own apartment
    // implicitly join the MTA.
    __try {
        do_request(ctx);
    } __except (GetExceptionCode() == EXCEPTION_ACCESS_VIOLATION
                    ? EXCEPTION_EXECUTE_HANDLER
                    : EXCEPTION_CONTINUE_SEARCH) {
        // ctx->success remains false
    }
    SetEvent(ctx->done_event);
    return 0;
}

bool smtc_try_request_manager(GlobalSystemMediaTransportControlsSessionManager* out,
                              JNIEnv* /*env*/) noexcept {
    RequestCtx ctx;
    ctx.done_event = CreateEventW(nullptr, FALSE, FALSE, nullptr);
    if (!ctx.done_event) return false;

    HANDLE thread = CreateThread(nullptr, 0, request_thread, &ctx, 0, nullptr);
    if (!thread) {
        CloseHandle(ctx.done_event);
        return false;
    }

    // 5-second timeout covers worst-case cold-boot broker initialisation.
    WaitForSingleObject(ctx.done_event, 5000);
    CloseHandle(thread);
    CloseHandle(ctx.done_event);

    if (ctx.success) {
        *out = std::move(ctx.result);
        return true;
    }
    return false;
}
