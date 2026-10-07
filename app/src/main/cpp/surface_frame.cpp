#include <jni.h>
#include <dlfcn.h>
#include <link.h>
#include <unistd.h>
#include <cstdint>
#include <cstring>
#include <functional>
#include <memory>
#include <mutex>
#include <unordered_map>

// Exact myron OS4.0.0.30 ABI, pinned by NativeSurfaceFrame before loading.
// No pixel API, window mutation, fence wait or producer hook runs here.
namespace {
JavaVM* vm;
struct BorrowedStrong { void* pointer; };
struct SingletonStrong {
    void* pointer = nullptr;
    SingletonStrong() = default;
    ~SingletonStrong() {} // One process-lifetime reference to the native singleton.
};
struct SurfaceStats { alignas(8) char bytes[0xa8]; };
using Callback=std::function<void(void*,int64_t,const BorrowedStrong&,const SurfaceStats&)>;
static_assert(sizeof(Callback)==48 && alignof(Callback)==16,"Target std::function ABI required");
using GetInstance=SingletonStrong(*)();
using Add=void(*)(void*,void*,void*,const BorrowedStrong&,Callback);
using Remove=void(*)(void*,void*,void*);
Add add;
Remove removeListener;
void* singleton;
std::mutex registrationsMutex;
int64_t nextId=0;
struct Registration {
    jobject receiver;
    jmethodID method;
    std::mutex mutex;
    bool open=true;
    ~Registration() {
        JNIEnv* env=nullptr; bool attached=vm->GetEnv(reinterpret_cast<void**>(&env),JNI_VERSION_1_6)!=JNI_OK;
        if(attached && vm->AttachCurrentThread(&env,nullptr)!=JNI_OK)return;
        env->DeleteGlobalRef(receiver);
        if(attached)vm->DetachCurrentThread();
    }
};
std::unordered_map<int64_t,std::shared_ptr<Registration>> registrations;
void error(JNIEnv* env,const char* text) { env->ThrowNew(env->FindClass("java/lang/IllegalStateException"),text); }
// libgui is already a dependency of libandroid_runtime. Read its resident symbol
// table; do not load a second copy or change any linker namespace.
void* residentSymbol(const char* wanted) {
    struct Search {const char* name;void* result=nullptr;} search{wanted};
    dl_iterate_phdr([](dl_phdr_info* info,size_t,void* data){
        auto* s=static_cast<Search*>(data);
        const char* leaf=strrchr(info->dlpi_name,'/');leaf=leaf?leaf+1:info->dlpi_name;
        if(strcmp(leaf,"libgui.so"))return 0;
        auto base=info->dlpi_addr;const ElfW(Dyn)* dynamic=nullptr;
        for(size_t i=0;i<info->dlpi_phnum;i++)if(info->dlpi_phdr[i].p_type==PT_DYNAMIC)
            dynamic=reinterpret_cast<const ElfW(Dyn)*>(base+info->dlpi_phdr[i].p_vaddr);
        if(!dynamic)return 1;
        const ElfW(Sym)* symbols=nullptr;const char* strings=nullptr;const uint32_t* gnu=nullptr;uint32_t count=0;
        for(auto* d=dynamic;d->d_tag!=DT_NULL;d++) {
            uintptr_t address=d->d_un.d_ptr; if(address<base)address+=base;
            if(d->d_tag==DT_SYMTAB)symbols=reinterpret_cast<const ElfW(Sym)*>(address);
            if(d->d_tag==DT_STRTAB)strings=reinterpret_cast<const char*>(address);
            if(d->d_tag==DT_HASH)count=reinterpret_cast<const uint32_t*>(address)[1];
            if(d->d_tag==DT_GNU_HASH)gnu=reinterpret_cast<const uint32_t*>(address);
        }
        if(!symbols||!strings)return 1;
        if(!count&&gnu) {
            const uint32_t* buckets=gnu+4+gnu[2]*2;const uint32_t* chains=buckets+gnu[0];
            uint32_t max=0;for(uint32_t i=0;i<gnu[0];i++)if(buckets[i]>max)max=buckets[i];
            if(max>=gnu[1]){count=max;while(!(chains[count-gnu[1]]&1))count++;count++;}
        }
        if(count>1000000)return 1;
        for(uint32_t i=0;i<count;i++)if(symbols[i].st_shndx!=SHN_UNDEF&&strcmp(strings+symbols[i].st_name,s->name)==0){
            s->result=reinterpret_cast<void*>(base+symbols[i].st_value);break;
        }
        return 1;
    },&search);
    return search.result;
}
}
extern "C" JNIEXPORT jint JNICALL JNI_OnLoad(JavaVM* supplied,void*) { vm=supplied;return JNI_VERSION_1_6; }
extern "C" JNIEXPORT void JNICALL
Java_com_zhuanz_flymemotion_hook_NativeSurfaceFrame_initialize(JNIEnv* env,jclass) {
    if(singleton)return;
    auto get=reinterpret_cast<GetInstance>(residentSymbol("_ZN7android28TransactionCompletedListener11getInstanceEv"));
    add=reinterpret_cast<Add>(residentSymbol("_ZN7android28TransactionCompletedListener23addSurfaceStatsListenerEPvS1_RKNS_2spINS_14SurfaceControlEEENSt3__18functionIFvS1_lRKNS2_INS_5FenceEEERKNS_12SurfaceStatsEEEE"));
    removeListener=reinterpret_cast<Remove>(residentSymbol("_ZN7android28TransactionCompletedListener26removeSurfaceStatsListenerEPvS1_"));
    if(!get||!add||!removeListener){error(env,"Target SurfaceStats ABI unavailable");return;}
    static auto reference=get();singleton=reference.pointer;
    if(!singleton)error(env,"Target SurfaceStats singleton unavailable");
}
extern "C" JNIEXPORT jlong JNICALL
Java_com_zhuanz_flymemotion_hook_NativeSurfaceFrame_register(JNIEnv* env,jclass,jlong nativeSurface,jobject receiver) {
    if(!singleton||!nativeSurface){error(env,"Missing SurfaceStats binding");return 0;}
    auto state=std::make_shared<Registration>();state->receiver=env->NewGlobalRef(receiver);
    state->method=env->GetMethodID(env->GetObjectClass(receiver),"onFrame","(JJJI)V");
    if(!state->method)return 0;
    int64_t id;
    {std::lock_guard lock(registrationsMutex);id=++nextId;registrations.emplace(id,state);}
    BorrowedStrong surface{reinterpret_cast<void*>(nativeSurface)};
    add(singleton,reinterpret_cast<void*>(id),reinterpret_cast<void*>(id),surface,
        [state](void*,int64_t latch,const BorrowedStrong& fence,const SurfaceStats& stats){
            uint64_t frame=0,previous=0;
            std::memcpy(&frame,stats.bytes+0x90,sizeof(frame));
            std::memcpy(&previous,stats.bytes+0x98,sizeof(previous));
            // Native on-commit statistics have no positive latch. Never qualify those.
            if(!frame||frame<=previous||latch<=0||!fence.pointer)return;
            int fd=-1;
            std::memcpy(&fd,static_cast<const char*>(fence.pointer)+0xc,sizeof(fd));
            if(fd<0)return;
            int owned=dup(fd);if(owned<0)return;
            JNIEnv* callbackEnv=nullptr;
            bool attached=vm->GetEnv(reinterpret_cast<void**>(&callbackEnv),JNI_VERSION_1_6)!=JNI_OK;
            if(attached&&vm->AttachCurrentThread(&callbackEnv,nullptr)!=JNI_OK){close(owned);return;}
            {std::lock_guard lock(state->mutex);
                if(state->open){callbackEnv->CallVoidMethod(state->receiver,state->method,
                    static_cast<jlong>(frame),static_cast<jlong>(previous),static_cast<jlong>(latch),owned);
                    // Receiver owns the duplicate even when its proof has become stale.
                    if(callbackEnv->ExceptionCheck()){callbackEnv->ExceptionClear();close(owned);}
                }else close(owned);
            }
            if(attached)vm->DetachCurrentThread();
        });
    return id;
}
extern "C" JNIEXPORT void JNICALL
Java_com_zhuanz_flymemotion_hook_NativeSurfaceFrame_unregister(JNIEnv*,jclass,jlong id) {
    std::shared_ptr<Registration> state;
    {std::lock_guard lock(registrationsMutex);auto it=registrations.find(id);if(it==registrations.end())return;
        state=it->second;registrations.erase(it);}
    {std::lock_guard lock(state->mutex);state->open=false;}
    removeListener(singleton,reinterpret_cast<void*>(id),reinterpret_cast<void*>(id));
}
