// Copyright (c) 2015-present Mattermost, Inc. All Rights Reserved.
// See LICENSE.txt for license information.

import {CallsManager} from '@calls/calls_manager';
import {AppState, NativeModules} from 'react-native';
import {BackgroundTimer} from 'react-native-nitro-bg-timer-plus';
import DatabaseManager from '@database/manager';
import CallsNative from '@init/calls_native';
import {getAllServerCredentials} from '@init/credentials';
import ManagedApp from '@init/managed_app';
import PushNotifications from '@init/push_notifications';
import GlobalEventHandler from '@managers/global_event_handler';
import NetworkManager from '@managers/network_manager';
import OfflinePersistenceManager from '@managers/offline_persistence_manager';
import SecurityManager from '@managers/security_manager';
import SessionManager from '@managers/session_manager';
import WebsocketManager from '@managers/websocket_manager';
import EphemeralStore from '@store/ephemeral_store';
import {NavigationStore} from '@store/navigation_store';
import {checkForUpdate} from '@utils/ota_update';
import {syncAllSettingsFromServer} from '@utils/webview_setting';

// Controls whether the main initialization (database, etc...) is done, either on app launch
// or on the Share Extension, for example.
let baseAppInitialized = false;
let serverCredentials: ServerCredential[] = [];

// Fallback Polyfill for Promise.allSettle
Promise.allSettled = Promise.allSettled || (<T>(promises: Array<Promise<T>>) => Promise.all(
    promises.map((p) => p.
        then((value) => ({
            status: 'fulfilled',
            value,
        })).
        catch((reason) => ({
            status: 'rejected',
            reason,
        })),
    ),
));

export async function initialize() {
    if (!baseAppInitialized) {
        baseAppInitialized = true;
        serverCredentials = await getAllServerCredentials();
        const serverUrls = serverCredentials.map((credential) => credential.serverUrl);

        await DatabaseManager.init(serverUrls);
        await NetworkManager.init(serverCredentials);

        // OfflinePersistenceManager init runs before WS init so any pending wipes
        // complete before WebSocket clients start populating server databases.
        await OfflinePersistenceManager.init(serverCredentials);
        await WebsocketManager.init(serverCredentials);

        // 从服务端同步 WebView 域名白名单，并把登录态同步给原生保活服务
        // （token/userId 供原生 WebSocket 在 JS 不可用时接管推送）
        for (const cred of serverCredentials) {
            syncAllSettingsFromServer(cred.serverUrl);
            try {
                NativeModules.DaemonStartModule.saveToken(cred.serverUrl, cred.token, cred.userId);
            } catch {}
        }

        // JS WebSocket 健康心跳：原生服务据此判断后台时是否需要接管推送。
        // 进程被杀/冻结/断连时心跳消失，原生在 90 秒内自动接管。
        try {
            BackgroundTimer.setInterval(() => {
                try {
                    const connected = serverCredentials.some(
                        (c) => WebsocketManager.getClient(c.serverUrl)?.isConnected(),
                    );
                    NativeModules.DaemonStartModule.heartbeat(connected);
                } catch {}
            }, 30000);
        } catch {}
    }

    NavigationStore.reset();
    EphemeralStore.setCurrentThreadId('');
    EphemeralStore.setProcessingNotification('');

    await SecurityManager.init();

    GlobalEventHandler.init();
    ManagedApp.init();
    SessionManager.init();
    CallsManager.initialize();
    CallsNative.init();

    PushNotifications.init(serverCredentials.length > 0);

    // OTA 更新检查（异步，不阻塞启动）
    checkForUpdate();

    // 启动保活前台服务（服务内含原生 WebSocket，重启/被杀后通知能力随服务恢复）
    try { NativeModules.DaemonStartModule.startDaemon(); } catch {}

    // 前后台通知原生：前台时原生 WebSocket 让位给 JS，后台时按心跳策略接管
    AppState.addEventListener('change', (state) => {
        const isActive = state === 'active';
        try { NativeModules.DaemonStartModule.setForeground(isActive); } catch {}
    });
}

export function cleanup() {
    ManagedApp.cleanup();
    GlobalEventHandler.cleanup();
    SecurityManager.cleanup();
    SessionManager.cleanup();
    CallsManager.cleanup();
    CallsNative.cleanup();
    PushNotifications.cleanup();
    OfflinePersistenceManager.cleanup();
}
