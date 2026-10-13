package com.firerpa.shizuku;

import android.os.Bundle;

interface IShizukuCommandService {
    Bundle runCommand(String script) = 1;
    int getServerUid() = 2;
    void destroy() = 16777114;
}
