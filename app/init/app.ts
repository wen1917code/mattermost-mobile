// Copyright (c) 2015-present Mattermost, Inc. All Rights Reserved.
// See LICENSE.txt for license information.

import {CallsManager} from '@calls/calls_manager';
import {AppState, NativeModules} from 'react-native';
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

        // 从服务端同步 WebView 域名白名单
        for (const cred of serverCredentials) {
            syncAllSettingsFromServer(cred.serverUrl);
        }
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

    // 启动双进程守护
    try { NativeModules.DaemonStartModule.startDaemon(); } catch {}

    // 保活：通知原生层前台状态，后台时启动 1px Activity
    AppState.addEventListener('change', (state) => {
        const isActive = state === 'active';
        try { NativeModules.DaemonStartModule.setForeground(isActive); } catch {}
        if (!isActive) {
            try { NativeModules.DaemonStartModule.showKeepAlive(); } catch {}
        }
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
