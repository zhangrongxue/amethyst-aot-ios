#import <Foundation/Foundation.h>

#ifdef __cplusplus
extern "C" {
#endif

/**
 * Calls dev.mcjava.spike.AotMain.main({username, versionId}) on the RoboVM VM
 * created by the embedded McAotFramework. Returns 0 on success, -1 when the VM
 * or the class is unavailable.
 */
int AOTBridgeLaunchGame(const char *username, const char *versionId);

/** YES when the app was built with the AOT framework linked in. */
BOOL AOTBridgeAvailable(void);

#ifdef __cplusplus
}
#endif
