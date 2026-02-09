#import <Foundation/Foundation.h>

#ifdef __cplusplus
extern "C" {
#endif

void adapter_get(void);
void adapter_get_env(void);
void adapter_stream(void);
void adapter_stream_env(void);
void adapter_send(int command);
void adapter_send_env(void);
void adapter_seek(long position);
void adapter_seek_env(void);
void adapter_shuffle(int mode);
void adapter_shuffle_env(void);
void adapter_repeat(int mode);
void adapter_repeat_env(void);
void adapter_speed(int speed);
void adapter_speed_env(void);
void adapter_test(void);

#ifdef __cplusplus
}
#endif
