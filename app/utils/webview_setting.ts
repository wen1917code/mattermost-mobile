// Copyright (c) 2015-present Mattermost, Inc. All Rights Reserved.
// See LICENSE.txt for license information.

import AsyncStorage from '@react-native-async-storage/async-storage';
import {getServerCredentials} from '@init/credentials';

const TOGGLE_KEY = 'force_webview_other';
const DOMAINS_KEY = 'custom_webview_domains';
const API_PATH = '/api/v4/users/me/webview-domains';

let cachedToggle: boolean | null = null;
let cachedDomains: string[] | null = null;

// ── 开关 ──

export async function loadWebViewSetting(): Promise<boolean> {
    try {
        const value = await AsyncStorage.getItem(TOGGLE_KEY);
        cachedToggle = value === 'true';
        return cachedToggle;
    } catch {
        cachedToggle = false;
        return false;
    }
}

export async function setWebViewSetting(enabled: boolean, serverUrl?: string): Promise<void> {
    cachedToggle = enabled;
    try {
        await AsyncStorage.setItem(TOGGLE_KEY, String(enabled));
        if (serverUrl) pushSettingToServer(serverUrl, 'force_webview', String(enabled));
    } catch {
        // ignore
    }
}

export function isWebViewForced(): boolean {
    if (cachedToggle === null) {
        loadWebViewSetting();
        return false;
    }
    return cachedToggle;
}

// ── 自定义域名白名单 ──

async function loadDomains(): Promise<string[]> {
    try {
        const raw = await AsyncStorage.getItem(DOMAINS_KEY);
        cachedDomains = raw ? JSON.parse(raw) : [];
        return cachedDomains;
    } catch {
        cachedDomains = [];
        return [];
    }
}

async function saveDomains(domains: string[]): Promise<void> {
    cachedDomains = domains;
    try {
        await AsyncStorage.setItem(DOMAINS_KEY, JSON.stringify(domains));
    } catch {
        // ignore
    }
}

export async function getCustomDomains(): Promise<string[]> {
    if (cachedDomains === null) {
        return loadDomains();
    }
    return cachedDomains;
}

export async function addCustomDomain(domain: string, serverUrl?: string): Promise<void> {
    const domains = await getCustomDomains();
    const normalized = domain.toLowerCase().replace(/^https?:\/\//, '').replace(/\/.*$/, '');
    if (!domains.includes(normalized)) {
        domains.push(normalized);
        await saveDomains(domains);
        if (serverUrl) {
            pushDomainsToServer(serverUrl, domains);
        }
    }
}

export async function removeCustomDomain(domain: string, serverUrl?: string): Promise<void> {
    const domains = await getCustomDomains();
    const idx = domains.indexOf(domain);
    if (idx !== -1) {
        domains.splice(idx, 1);
        await saveDomains(domains);
        if (serverUrl) {
            pushDomainsToServer(serverUrl, domains);
        }
    }
}

export function isExceptionDomain(hostname: string): boolean {
    if (!cachedDomains) {
        return false;
    }
    // 支持通配符：*.example.com 匹配 sub.example.com 和 example.com
    return cachedDomains.some((pattern) => {
        if (pattern.startsWith('*.')) {
            const suffix = pattern.slice(1); // .example.com
            return hostname.endsWith(suffix) || hostname === pattern.slice(2);
        }
        return hostname === pattern || hostname.endsWith('.' + pattern);
    });
}

// ── 服务端同步 ──

async function getServerAuth(serverUrl: string): Promise<{token: string} | null> {
    try {
        const cred = await getServerCredentials(serverUrl);
        if (!cred?.token) return null;
        return {token: cred.token};
    } catch {
        return null;
    }
}

export async function syncDomainsFromServer(serverUrl: string): Promise<void> {
    try {
        const auth = await getServerAuth(serverUrl);
        if (!auth) return;
        const res = await fetch(`${serverUrl}${API_PATH}`, {
            headers: {Authorization: `Bearer ${auth.token}`},
        });
        if (!res.ok) return;
        const data = await res.json();
        const domains: string[] = Array.isArray(data?.domains) ? data.domains : [];
        cachedDomains = domains;
        await AsyncStorage.setItem(DOMAINS_KEY, JSON.stringify(domains));
    } catch {
        // 静默失败，保留本地缓存
    }
}

export async function pushDomainsToServer(serverUrl: string, domains: string[]): Promise<void> {
    try {
        const auth = await getServerAuth(serverUrl);
        if (!auth) return;
        await fetch(`${serverUrl}${API_PATH}`, {
            method: 'PUT',
            headers: {
                Authorization: `Bearer ${auth.token}`,
                'Content-Type': 'application/json',
            },
            body: JSON.stringify({domains}),
        });
    } catch {
        // 静默失败，下次登录拉取时覆盖
    }
}

// ── 简略通知 ──

const SUMMARY_KEY = 'notification_summary_enabled';

let cachedSummary: boolean | null = null;

export async function loadSummarySetting(): Promise<boolean> {
    try {
        const val = await AsyncStorage.getItem(SUMMARY_KEY);
        cachedSummary = val === 'true';
        return cachedSummary;
    } catch {
        cachedSummary = false;
        return false;
    }
}

export async function setSummarySetting(enabled: boolean, serverUrl?: string): Promise<void> {
    cachedSummary = enabled;
    try {
        await AsyncStorage.setItem(SUMMARY_KEY, String(enabled));
        if (serverUrl) pushSettingToServer(serverUrl, 'notification_summary', String(enabled));
    } catch {}
}

export function isSummaryEnabled(): boolean {
    if (cachedSummary === null) { loadSummarySetting(); return false; }
    return cachedSummary;
}

// ── 自定义心跳间隔 ──

const HEARTBEAT_KEY = 'websocket_heartbeat_interval';

let cachedHeartbeat: number | null = null;

export async function loadHeartbeatSetting(): Promise<number> {
    try {
        const val = await AsyncStorage.getItem(HEARTBEAT_KEY);
        cachedHeartbeat = val ? parseInt(val, 10) : 30;
        return cachedHeartbeat;
    } catch {
        cachedHeartbeat = 30;
        return 30;
    }
}

export async function setHeartbeatSetting(seconds: number, serverUrl?: string): Promise<void> {
    cachedHeartbeat = seconds;
    try {
        await AsyncStorage.setItem(HEARTBEAT_KEY, String(seconds));
        if (serverUrl) pushSettingToServer(serverUrl, 'heartbeat', String(seconds));
    } catch {}
}

export function getHeartbeatInterval(): number {
    if (cachedHeartbeat === null) { loadHeartbeatSetting(); return 30; }
    return cachedHeartbeat;
}

// ── 云同步 ──

const PREF_API = '/api/v4/users/me/preferences';
const CATEGORY = 'struggle_settings';

export async function syncAllSettingsFromServer(serverUrl: string): Promise<void> {
    try {
        const cred = await getServerCredentials(serverUrl);
        if (!cred?.token) return;
        const res = await fetch(`${serverUrl}${PREF_API}`, {
            headers: {Authorization: `Bearer ${cred.token}`},
        });
        if (!res.ok) return;
        const prefs: Array<{category: string; name: string; value: string}> = await res.json();
        for (const p of prefs) {
            if (p.category !== CATEGORY) continue;
            switch (p.name) {
                case 'force_webview':
                    cachedToggle = p.value === 'true';
                    await AsyncStorage.setItem(TOGGLE_KEY, p.value);
                    break;
                case 'notification_summary':
                    cachedSummary = p.value === 'true';
                    await AsyncStorage.setItem(SUMMARY_KEY, p.value);
                    break;
                case 'heartbeat':
                    cachedHeartbeat = parseInt(p.value, 10) || 30;
                    await AsyncStorage.setItem(HEARTBEAT_KEY, p.value);
                    break;
                case 'domains':
                    cachedDomains = JSON.parse(p.value);
                    await AsyncStorage.setItem(DOMAINS_KEY, p.value);
                    break;
            }
        }
    } catch {
        // 静默失败
    }
}

async function pushPreference(serverUrl: string, token: string, name: string, value: string): Promise<void> {
    await fetch(`${serverUrl}${PREF_API}`, {
        method: 'PUT',
        headers: {
            Authorization: `Bearer ${token}`,
            'Content-Type': 'application/json',
        },
        body: JSON.stringify([{category: CATEGORY, name, value}]),
    });
}

export async function pushSettingToServer(serverUrl: string, name: string, value: string): Promise<void> {
    try {
        const cred = await getServerCredentials(serverUrl);
        if (!cred?.token) return;
        await pushPreference(serverUrl, cred.token, name, value);
    } catch {
        // 静默失败
    }
}

export async function pushAllSettingsToServer(serverUrl: string): Promise<void> {
    try {
        const cred = await getServerCredentials(serverUrl);
        if (!cred?.token) return;
        const domains = await getCustomDomains();
        const prefs = [
            {category: CATEGORY, name: 'force_webview', value: String(isWebViewForced())},
            {category: CATEGORY, name: 'notification_summary', value: String(isSummaryEnabled())},
            {category: CATEGORY, name: 'heartbeat', value: String(getHeartbeatInterval())},
            {category: CATEGORY, name: 'domains', value: JSON.stringify(domains)},
        ];
        await fetch(`${serverUrl}${PREF_API}`, {
            method: 'PUT',
            headers: {
                Authorization: `Bearer ${cred.token}`,
                'Content-Type': 'application/json',
            },
            body: JSON.stringify(prefs),
        });
    } catch {
        // 静默失败
    }
}
