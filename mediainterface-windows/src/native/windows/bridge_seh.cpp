#include "bridge_shared.h"
#include <Windows.h>
#include <atomic>
#include <new>

// ---------------------------------------------------------------------------
// Dedicated Win32 thread for the cold-boot RequestAsync() call.
//
// On the first launch after a reboot, the WinRT activation broker may not
// have finished its one-time factory initialisation for the SMTC reader API.
// RequestAsync().get() can fault with 0xC0000005 inside COM's proxy code,
// or throw a C++/WinRT hresult_error (SEH code 0xE06D7363).
//
// Running on a plain CreateThread thread (not a JVM-managed thread) means
// there are no JVM SEH handlers around our code. The two-layer defence
// below catches both failure modes:
//
//   1. try/catch(...)   in do_request  — handles C++ exceptions (hresult_error,
//                                        apartment failures, any WinRT throw).
//   2. __try/__except   in request_thread — handles raw SEH faults (AV, stowed
//                                          exception, etc.) that bypass C++.
//
// Once the factory has warmed up successfully once, request_manager_safe()
// switches to direct calls with no thread-creation overhead.
//
// RequestCtx is heap-allocated with a two-party refcount so that a 5-second
// timeout in the caller cannot cause a use-after-free: whichever side
// (caller or worker) releases last also frees the context.
// ---------------------------------------------------------------------------

struct RequestCtx {
    GlobalSystemMediaTransportControlsSessionManager result{nullptr};
    HANDLE done_event = nullptr;
    bool   success    = false;

    // Two-party refcount: caller holds 1, worker holds 1.
    // Last party to release frees the struct and closes the event.
    std::atomic<int> refs{2};
};

static void ctx_release(RequestCtx* ctx) {
    if (ctx->refs.fetch_sub(1, std::memory_order_acq_rel) == 1) {
        // We are the last owner.
        if (ctx->done_event) CloseHandle(ctx->done_event);
        delete ctx;
    }
}

// Inner helper: owns WinRT C++ objects (and therefore dtors).
// Marked noinline so the optimiser cannot merge it with the outer __try
// function — merging would put C++ dtors in scope alongside __try, which
// triggers MSVC error C2712.
__declspec(noinline)
static void do_request(RequestCtx* ctx) {
    try {
        // Explicitly join the MTA on this worker thread.  On cold boot the
        // implicit-MTA join is exactly the code path that can fault, so
        // making apartment state deterministic avoids that race.
        // S_FALSE is returned if already initialised — that is fine.
        winrt::init_apartment(winrt::apartment_type::multi_threaded);

        ctx->result  = GlobalSystemMediaTransportControlsSessionManager::RequestAsync().get();
        ctx->success = true;
    } catch (...) {
        // C++ exceptions: hresult_error, apartment init failure, etc.
        // ctx->success stays false — caller will retry.
    }
}

// Worker thread: no local C++ dtors in scope, so __try/__except is valid.
static DWORD WINAPI request_thread(LPVOID param) noexcept {
    auto* ctx = static_cast<RequestCtx*>(param);

    __try {
        do_request(ctx);
    } __except (EXCEPTION_EXECUTE_HANDLER) {
        // Catch ALL SEH exceptions (AV, stowed exceptions, C++ exceptions
        // that somehow bypassed the inner try/catch, etc.).
        // ctx->success stays false.
    }

    SetEvent(ctx->done_event);
    ctx_release(ctx);
    return 0;
}

bool smtc_try_request_manager(GlobalSystemMediaTransportControlsSessionManager* out,
                              JNIEnv* /*env*/) noexcept {
    auto* ctx = new (std::nothrow) RequestCtx;
    if (!ctx) return false;

    ctx->done_event = CreateEventW(nullptr, FALSE, FALSE, nullptr);
    if (!ctx->done_event) {
        delete ctx;
        return false;
    }

    HANDLE thread = CreateThread(nullptr, 0, request_thread, ctx, 0, nullptr);
    if (!thread) {
        CloseHandle(ctx->done_event);
        delete ctx;
        return false;
    }
    // We don't need the thread handle; the event provides synchronisation.
    CloseHandle(thread);

    // 5-second timeout covers worst-case cold-boot broker initialisation.
    // If the wait succeeds (WAIT_OBJECT_0), the happens-before edge from
    // SetEvent guarantees visibility of ctx->success and ctx->result.
    // If the wait times out, the worker may still be running — the refcount
    // on ctx ensures the worker can safely finish without a use-after-free.
    bool ok = (WaitForSingleObject(ctx->done_event, 5000) == WAIT_OBJECT_0)
              && ctx->success;

    if (ok) {
        *out = std::move(ctx->result);
    }

    ctx_release(ctx);
    return ok;
}
