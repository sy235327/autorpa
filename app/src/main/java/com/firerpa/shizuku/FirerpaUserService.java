package com.firerpa.shizuku;

import android.content.Context;
import android.os.Bundle;
import android.system.Os;

import androidx.annotation.Keep;

import java.io.ByteArrayOutputStream;

@Keep
public class FirerpaUserService extends IShizukuCommandService.Stub {

    public static final String KEY_SUCCESS = "success";
    public static final String KEY_EXIT_CODE = "exit_code";
    public static final String KEY_OUTPUT = "output";
    public static final String KEY_SERVER_UID = "server_uid";

    public FirerpaUserService() {
    }

    @Keep
    public FirerpaUserService(Context context) {
        this();
    }

    @Override
    public Bundle runCommand(String script) {
        return runShell(script);
    }

    @Override
    public int getServerUid() {
        return Os.getuid();
    }

    @Override
    public void destroy() {
        System.exit(0);
    }

    private Bundle runShell(String script) {
        try {
            Process process = new ProcessBuilder("sh").redirectErrorStream(true).start();
            process.getOutputStream().write((script + "\nexit\n").getBytes());
            process.getOutputStream().flush();
            process.getOutputStream().close();

            ByteArrayOutputStream output = new ByteArrayOutputStream();
            byte[] buffer = new byte[8192];
            int read;
            while ((read = process.getInputStream().read(buffer)) != -1) {
                output.write(buffer, 0, read);
            }
            int exitCode = process.waitFor();

            Bundle bundle = new Bundle();
            bundle.putBoolean(KEY_SUCCESS, exitCode == 0);
            bundle.putInt(KEY_EXIT_CODE, exitCode);
            bundle.putString(KEY_OUTPUT, output.toString().trim());
            bundle.putInt(KEY_SERVER_UID, getServerUid());
            return bundle;
        } catch (Throwable t) {
            Bundle bundle = new Bundle();
            bundle.putBoolean(KEY_SUCCESS, false);
            bundle.putInt(KEY_EXIT_CODE, -1);
            bundle.putString(KEY_OUTPUT, t.getMessage() != null ? t.getMessage() : t.getClass().getSimpleName());
            bundle.putInt(KEY_SERVER_UID, getServerUid());
            return bundle;
        }
    }
}
