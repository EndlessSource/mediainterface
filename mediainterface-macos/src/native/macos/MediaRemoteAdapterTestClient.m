#import <Foundation/Foundation.h>

int main() {
    @autoreleasepool {
        printf("setup_done\n");
        fflush(stdout);
        char buffer[256];
        while (fgets(buffer, sizeof(buffer), stdin)) {
            if (strncmp(buffer, "cleanup", 7) == 0) {
                break;
            }
        }
    }
    return 0;
}
