// Copyright (c) 2015-present Mattermost, Inc. All Rights Reserved.
// StriGGle OTA Update

import {nativeApplicationVersion as getNativeVersion} from 'expo-application';
import * as FileSystem from 'expo-file-system/legacy';
import {Alert, NativeModules} from 'react-native';

const UPDATE_URL = 'https://dl.hegoulaogouzi.icu/version.json';
const APK_FILENAME = 'StruGGle.apk';

let lastNotifiedPercent = -1;

function showProgress(percent: number): void {
    if (percent === lastNotifiedPercent) return;
    lastNotifiedPercent = percent;
    NativeModules.OtaNotificationModule.showProgress(percent);
}

function dismiss(): void {
    lastNotifiedPercent = -1;
    NativeModules.OtaNotificationModule.dismiss();
}

function showFailed(): void {
    lastNotifiedPercent = -1;
    NativeModules.OtaNotificationModule.showFailed();
}

function compareVersions(local: string, remote: string): boolean {
    const lp = local.split('.').map(Number);
    const rp = remote.split('.').map(Number);
    for (let i = 0; i < Math.max(lp.length, rp.length); i++) {
        const l = lp[i] || 0;
        const r = rp[i] || 0;
        if (r > l) return true;
        if (r < l) return false;
    }
    return false;
}

function showUpdateDialog(version: string, onUpdate: () => void): void {
    Alert.alert(
        '发现新版本',
        `检测到新版本 ${version}，需要更新后才能继续使用。`,
        [{text: '立即更新', onPress: onUpdate}],
        {cancelable: false},
    );
}

export async function checkForUpdate(): Promise<void> {
    try {
        const localVersion = getNativeVersion;
        if (!localVersion) return;

        const response = await fetch(UPDATE_URL, {headers: {'Cache-Control': 'no-cache'}});
        if (!response.ok) return;

        const data: {version: string; url: string} = await response.json();
        if (!data?.version || !data?.url) return;

        if (!compareVersions(localVersion, data.version)) return;

        showUpdateDialog(data.version, () => {
            downloadAndInstall(data.url);
        });
    } catch {
        // 静默失败
    }
}

async function downloadAndInstall(url: string): Promise<void> {
    try {
        const dest = FileSystem.cacheDirectory + APK_FILENAME;
        try { await FileSystem.deleteAsync(dest, {idempotent: true}); } catch {}

        showProgress(0);

        const resumable = FileSystem.createDownloadResumable(
            url,
            dest,
            {},
            ({totalBytesWritten, totalBytesExpectedToWrite}) => {
                if (totalBytesExpectedToWrite > 0) {
                    showProgress(Math.round((totalBytesWritten / totalBytesExpectedToWrite) * 100));
                }
            },
        );

        const result = await resumable.downloadAsync();
        if (!result || result.status !== 200) {
            showFailed();
            throw new Error(`下载失败: HTTP ${result?.status}`);
        }

        dismiss();
        NativeModules.InstallApkModule.installApk(result.uri);
    } catch (error: any) {
        showFailed();
        Alert.alert('更新失败', error?.message || `${error}`);
    }
}
