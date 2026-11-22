// IShizukuShellService.aidl
package com.miniichatNext.carter.data.tools;

interface IShizukuShellService {
    String execute(String script, int timeoutSeconds);
}
