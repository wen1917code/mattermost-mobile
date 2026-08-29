// Copyright (c) 2015-present Mattermost, Inc. All Rights Reserved.
// See LICENSE.txt for license information.

import React, {useCallback, useEffect, useState} from 'react';
import {useIntl} from 'react-intl';
import {Alert, NativeModules, Pressable, type PressableStateCallbackType, Text, TextInput, TouchableOpacity, View} from 'react-native';

import BoolSetting from '@components/settings/bool_setting';
import SettingContainer from '@components/settings/container';
import SettingOption from '@components/settings/option';
import SettingSeparator from '@components/settings/separator';
import {Screens} from '@constants';
import {useServerUrl} from '@context/server';
import {useTheme} from '@context/theme';
import useAndroidHardwareBackHandler from '@hooks/android_back_handler';
import useDidMount from '@hooks/did_mount';
import {usePreventDoubleTap} from '@hooks/utils';
import {navigateBack, navigateToSettingsScreen} from '@screens/navigation';
import {deleteFileCache, getAllFilesInCachesDirectory, getFormattedFileSize} from '@utils/file';
import {changeOpacity, makeStyleSheetFromTheme} from '@utils/theme';
import {addCustomDomain, getCustomDomains, loadWebViewSetting, removeCustomDomain, setWebViewSetting, loadHeartbeatSetting, setHeartbeatSetting, getHeartbeatInterval} from '@utils/webview_setting';
import {getServerCredentials} from '@init/credentials';

import type {FileInfo} from 'expo-file-system';

const EMPTY_FILES: FileInfo[] = [];

const getStyleSheet = makeStyleSheetFromTheme((theme: Theme) => ({
    domainSection: {
        marginTop: 0,
    },
    inputRow: {
        flexDirection: 'row',
        paddingHorizontal: 15,
        paddingVertical: 10,
        alignItems: 'center',
    },
    input: {
        flex: 1,
        backgroundColor: changeOpacity(theme.centerChannelColor, 0.08),
        borderRadius: 8,
        paddingHorizontal: 12,
        paddingVertical: 8,
        fontSize: 14,
        color: theme.centerChannelColor,
        marginRight: 10,
    },
    addBtn: {
        backgroundColor: theme.buttonBg,
        paddingHorizontal: 16,
        paddingVertical: 8,
        borderRadius: 8,
    },
    addBtnText: {
        color: theme.buttonColor,
        fontSize: 14,
        fontWeight: '600',
    },
    domainItem: {
        flexDirection: 'row',
        alignItems: 'center',
        paddingHorizontal: 15,
        paddingVertical: 12,
        borderBottomWidth: 1,
        borderBottomColor: changeOpacity(theme.centerChannelColor, 0.08),
    },
    domainText: {
        flex: 1,
        fontSize: 15,
        color: theme.centerChannelColor,
    },
    delBtn: {
        padding: 8,
    },
    delText: {
        color: theme.errorTextColor,
        fontSize: 14,
    },
    empty: {
        textAlign: 'center',
        color: changeOpacity(theme.centerChannelColor, 0.5),
        fontSize: 13,
        paddingVertical: 20,
        paddingHorizontal: 15,
    },
}));

type AdvancedSettingsProps = {
    isDevMode: boolean;
};
const AdvancedSettings = ({
    isDevMode,
}: AdvancedSettingsProps) => {
    const intl = useIntl();
    const serverUrl = useServerUrl();
    const theme = useTheme();
    const style = getStyleSheet(theme);
    const [dataSize, setDataSize] = useState<number | undefined>(0);
    const [files, setFiles] = useState<FileInfo[]>(EMPTY_FILES);
    const [forceWebView, setForceWebView] = useState(false);
    const [domains, setDomains] = useState<string[]>([]);
    const [newDomain, setNewDomain] = useState('');
    const [heartbeat, setHeartbeat] = useState(30);
    const [heartbeatDraft, setHeartbeatDraft] = useState('30');

    useEffect(() => {
        loadWebViewSetting().then(setForceWebView);
        getCustomDomains().then(setDomains);
        loadHeartbeatSetting().then((v) => {
            const val = v || 30;
            setHeartbeat(val);
            setHeartbeatDraft(String(val));
        });
    }, []);

    const reloadDomains = useCallback(async () => {
        setDomains(await getCustomDomains());
    }, []);

    const handleWebViewToggle = useCallback((value: boolean) => {
        setForceWebView(value);
        setWebViewSetting(value, serverUrl);
    }, []);

    const handleHeartbeatChange = useCallback((text: string) => {
        setHeartbeatDraft(text);
    }, []);

    const handleHeartbeatConfirm = useCallback(() => {
        const v = parseInt(heartbeatDraft, 10);
        if (isNaN(v) || v < 5) {
            setHeartbeat(5);
            setHeartbeatDraft('30');
            setHeartbeatSetting(30, serverUrl);
        } else if (v > 300) {
            setHeartbeat(300);
            setHeartbeatDraft('300');
            setHeartbeatSetting(300, serverUrl);
        } else {
            setHeartbeat(v);
            setHeartbeatSetting(v, serverUrl);
        }
    }, [heartbeatDraft]);

    const [showPwdForm, setShowPwdForm] = useState(false);
    const [pwdCurrent, setPwdCurrent] = useState('');
    const [pwdNew, setPwdNew] = useState('');
    const [pwdConfirm, setPwdConfirm] = useState('');

    const handleChangePassword = useCallback(() => {
        setShowPwdForm((v) => !v);
    }, []);

    const submitChangePassword = useCallback(async () => {
        if (pwdNew !== pwdConfirm) {
            Alert.alert("错误", "两次新密码输入不一致");
            return;
        }
        if (!pwdCurrent || !pwdNew) {
            Alert.alert("错误", "请填写所有密码字段");
            return;
        }
        try {
            const cred = await getServerCredentials(serverUrl);
            if (!cred?.token) {
                Alert.alert("错误", "未登录");
                return;
            }
            const res = await fetch(serverUrl + "/api/v4/users/me/password", {
                method: "PUT",
                headers: {"Authorization": "Bearer " + cred.token, "Content-Type": "application/json"},
                body: JSON.stringify({current_password: pwdCurrent, new_password: pwdNew}),
            });
            if (res.ok) {
                Alert.alert("成功", "密码修改成功");
                setShowPwdForm(false);
                setPwdCurrent(''); setPwdNew(''); setPwdConfirm('');
            } else {
                const err = await res.json();
                Alert.alert("失败", err?.message || "密码修改失败");
            }
        } catch (e) {
            Alert.alert("错误", "网络连接失败");
        }
    }, [pwdCurrent, pwdNew, pwdConfirm, serverUrl]);

    const handleAddDomain = useCallback(async () => {
        const domain = newDomain.trim();
        if (!domain) {
            return;
        }
        if (!domain.includes('.')) {
            Alert.alert(
                intl.formatMessage({id: 'custom_domains.invalid', defaultMessage: '域名格式不正确'}),
                intl.formatMessage({id: 'custom_domains.invalid_hint', defaultMessage: '请输入类似 example.com 的域名'}),
            );
            return;
        }
        await addCustomDomain(domain, serverUrl);
        setNewDomain('');
        await reloadDomains();
    }, [newDomain, intl, reloadDomains]);

    const handleDelDomain = useCallback((domain: string) => {
        Alert.alert(
            intl.formatMessage({id: 'custom_domains.delete', defaultMessage: '删除域名'}),
            intl.formatMessage({id: 'custom_domains.delete_confirm', defaultMessage: '确定要从白名单中移除 {domain} 吗？'}, {domain}),
            [
                {text: intl.formatMessage({id: 'custom_domains.cancel', defaultMessage: '取消'}), style: 'cancel'},
                {text: intl.formatMessage({id: 'custom_domains.delete_btn', defaultMessage: '删除'}), style: 'destructive', onPress: async () => { await removeCustomDomain(domain, serverUrl); await reloadDomains(); }},
            ],
            {cancelable: true},
        );
    }, [intl, reloadDomains]);

    const getAllCachedFiles = useCallback(() => {
        const {totalSize = 0, files: cachedFiles} = getAllFilesInCachesDirectory(serverUrl);
        setDataSize(totalSize);
        setFiles(cachedFiles || EMPTY_FILES);
    }, [serverUrl]);

    const onPressDeleteData = usePreventDoubleTap(useCallback(async () => {
        try {
            if (files.length > 0) {
                const {formatMessage} = intl;

                Alert.alert(
                    formatMessage({id: 'settings.advanced.delete_data', defaultMessage: 'Delete local files'}),
                    formatMessage({
                        id: 'settings.advanced.delete_message.confirmation',
                        defaultMessage: '\nThis will delete all files downloaded through the app for this server. Please confirm to proceed.\n',
                    }),
                    [
                        {text: formatMessage({id: 'settings.advanced.cancel', defaultMessage: 'Cancel'}), style: 'cancel'},
                        {
                            text: formatMessage({id: 'settings.advanced.delete', defaultMessage: 'Delete'}),
                            style: 'destructive',
                            onPress: () => {
                                deleteFileCache(serverUrl);
                                getAllCachedFiles();
                            },
                        },
                    ],
                    {cancelable: false},
                );
            }
        } catch (e) {
            //do nothing
        }
    }, [files.length, getAllCachedFiles, intl, serverUrl]));

    const onPressComponentLibrary = useCallback(() => {
        navigateToSettingsScreen(Screens.COMPONENT_LIBRARY);
    }, []);

    useDidMount(() => {
        getAllCachedFiles();
    });

    useAndroidHardwareBackHandler(Screens.SETTINGS_ADVANCED, navigateBack);

    const hasData = Boolean(dataSize && dataSize > 0);

    const pressedStyleFn = useCallback(({pressed}: PressableStateCallbackType) => (pressed && hasData && {opacity: 0.72}), [hasData]);
    const pressedStyleDevFn = useCallback(({pressed}: PressableStateCallbackType) => (pressed && {opacity: 0.72}), []);

    return (
        <SettingContainer testID='advanced_settings'>
            <Pressable
                onPress={onPressDeleteData}
                disabled={!hasData}
                style={pressedStyleFn}
            >
                <SettingOption
                    destructive={true}
                    icon='trash-can-outline'
                    info={getFormattedFileSize(dataSize || 0)}
                    label={intl.formatMessage({id: 'settings.advanced.delete_data', defaultMessage: 'Delete local files'})}
                    testID='advanced_settings.delete_data.option'
                    type='none'
                />
                <SettingSeparator/>
            </Pressable>
            <SettingSeparator/>
            <BoolSetting
                label={intl.formatMessage({id: 'settings.force_webview', defaultMessage: '内置浏览器打开其他链接'})}
                placeholder={intl.formatMessage({id: 'settings.force_webview.desc', defaultMessage: '开启后所有外部链接默认内置浏览器打开。例外名单始终反向执行。支持 *.example.com'})}
                value={forceWebView}
                onChange={handleWebViewToggle}
                testID='advanced_settings.force_webview'
                location={Screens.SETTINGS_ADVANCED}
            />
            <View style={style.domainSection}>
                    <SettingSeparator/>
                    <View style={style.inputRow}>
                        <TextInput
                            style={style.input}
                            placeholder={intl.formatMessage({id: 'custom_domains.placeholder', defaultMessage: '输入域名，如 example.com'})}
                            placeholderTextColor={changeOpacity(theme.centerChannelColor, 0.4)}
                            value={newDomain}
                            onChangeText={setNewDomain}
                            autoCapitalize='none'
                            autoCorrect={false}
                            keyboardType='url'
                            returnKeyType='done'
                            onSubmitEditing={handleAddDomain}
                        />
                        <TouchableOpacity style={style.addBtn} onPress={handleAddDomain}>
                            <Text style={style.addBtnText}>
                                {intl.formatMessage({id: 'custom_domains.add', defaultMessage: '添加'})}
                            </Text>
                        </TouchableOpacity>
                    </View>
                    {domains.length === 0 ? (
                        <Text style={style.empty}>
                            {intl.formatMessage({id: 'custom_domains.empty', defaultMessage: '还没有添加任何域名'})}
                        </Text>
                    ) : (
                        domains.map((d) => (
                            <View key={d} style={style.domainItem}>
                                <Text style={style.domainText}>{d}</Text>
                                <TouchableOpacity style={style.delBtn} onPress={() => handleDelDomain(d)}>
                                    <Text style={style.delText}>
                                        {intl.formatMessage({id: 'custom_domains.remove', defaultMessage: '移除'})}
                                    </Text>
                                </TouchableOpacity>
                            </View>
                        ))
                    )}
                    <SettingSeparator/>
            </View>
            <SettingSeparator/>
            <View style={style.inputRow}>
                <TextInput
                    style={[style.input, {flex: 0}]}
                    placeholder={intl.formatMessage({id: 'settings.advanced.heartbeat', defaultMessage: '心跳间隔(秒)'})}
                    placeholderTextColor={changeOpacity(theme.centerChannelColor, 0.4)}
                    value={heartbeatDraft}
                    onChangeText={handleHeartbeatChange}
                    keyboardType='numeric'
                    maxLength={3}
                />
                <TouchableOpacity style={style.addBtn} onPress={handleHeartbeatConfirm}>
                    <Text style={style.addBtnText}>确认</Text>
                </TouchableOpacity>
                <Text style={{flex: 1, fontSize: 12, color: changeOpacity(theme.centerChannelColor, 0.5), marginLeft: 10}}>
                    {intl.formatMessage({id: 'settings.advanced.heartbeat.desc', defaultMessage: '数值越大越省电，越小连接越稳定。范围5-300秒'})}
                </Text>
            </View>
            <SettingSeparator/>
            <Pressable
                onPress={handleChangePassword}
            >
                <SettingOption
                    icon='lock'
                    label={intl.formatMessage({id: 'settings.advanced.change_password', defaultMessage: '修改密码'})}
                    testID='advanced_settings.change_password.option'
                    type='arrow'
                />
                <SettingSeparator/>
            </Pressable>
            <SettingSeparator/>
            <Pressable
                onPress={() => { try { NativeModules.DaemonStartModule.openBatterySettings(); } catch {} }}
            >
                <SettingOption
                    icon='battery-charging'
                    label={intl.formatMessage({id: 'settings.advanced.battery_opt', defaultMessage: '后台保活优化'})}
                    testID='advanced_settings.battery_opt.option'
                    type='arrow'
                />
                <SettingSeparator/>
            </Pressable>
            {isDevMode && (
                <Pressable
                    onPress={onPressComponentLibrary}
                    style={pressedStyleDevFn}
                >
                    <SettingOption
                        label={intl.formatMessage({id: 'settings.advanced.component_library', defaultMessage: 'Component library'})}
                        testID='advanced_settings.component_library.option'
                        type='none'
                    />
                    <SettingSeparator/>
                </Pressable>
            )}
        </SettingContainer>
    );
};

export default AdvancedSettings;
