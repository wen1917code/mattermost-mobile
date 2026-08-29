// Copyright (c) 2015-present Mattermost, Inc. All Rights Reserved.
// See LICENSE.txt for license information.

import React, {useCallback, useImperativeHandle, useRef, useState} from 'react';
import {useIntl} from 'react-intl';
import {StyleSheet, type StyleProp, type ViewStyle} from 'react-native';

import ServerIcon from '@components/server_icon';
import {useServerUrl} from '@context/server';
import {useTheme} from '@context/theme';
import {subscribeAllServers} from '@database/subscription/servers';
import {subscribeUnreadAndMentionsByServer, type UnreadObserverArgs} from '@database/subscription/unreads';
import useDidMount from '@hooks/did_mount';
import {sortServersByDisplayName} from '@utils/server';

import type ServersModel from '@typings/database/models/app/servers';
import type {UnreadMessages, UnreadSubscription} from '@typings/database/subscriptions';

export type ServersRef = {
    openServers: () => void;
}

export const SERVER_ITEM_HEIGHT = 75;
export const PUSH_ALERT_TEXT_HEIGHT = 42;
const subscriptions: Map<string, UnreadSubscription> = new Map();

const styles = StyleSheet.create({
    icon: {
        alignItems: 'center',
        justifyContent: 'center',
        position: 'absolute',
        zIndex: 10,
        top: 10,
        left: 16,
        width: 40,
        height: 40,
    },
});

type Props = {
    badgeBorderColor?: string;
    iconColor?: string;
    iconStyle?: StyleProp<ViewStyle>;
    testID?: string;
}

const Servers = React.forwardRef<ServersRef, Props>(({
    badgeBorderColor,
    iconColor,
    iconStyle = styles.icon,
    testID = 'channel_list.servers.server_icon',
}, ref) => {
    const intl = useIntl();
    const [total, setTotal] = useState<UnreadMessages>({mentions: 0, unread: false});
    const registeredServers = useRef<ServersModel[] | undefined>(undefined);
    const currentServerUrl = useServerUrl();
    const theme = useTheme();

    const updateTotal = () => {
        let unread = false;
        let mentions = 0;
        subscriptions.forEach((value) => {
            unread = unread || value.unread;
            mentions += value.mentions;
        });
        setTotal({mentions, unread});
    };

    const unreadsSubscription = (serverUrl: string, {myChannels, settings, threadMentionCount, threadUnreads}: UnreadObserverArgs) => {
        const unreads = subscriptions.get(serverUrl);
        if (unreads) {
            let mentions = 0;
            let unread = Boolean(threadUnreads);
            for (const myChannel of myChannels) {
                const isMuted = settings?.[myChannel.id]?.mark_unread === 'mention';
                mentions += isMuted ? 0 : myChannel.mentionsCount;
                unread = unread || (myChannel.isUnread && !isMuted);
            }

            unreads.mentions = mentions + threadMentionCount;
            unreads.unread = unread;
            subscriptions.set(serverUrl, unreads);
            updateTotal();
        }
    };

    const serversObserver = async (servers: ServersModel[]) => {
        registeredServers.current = sortServersByDisplayName(servers, intl);

        // unsubscribe mentions from servers that were removed
        const allUrls = new Set(servers.map((s) => s.url));
        const subscriptionsToRemove = [...subscriptions].filter(([key]) => !allUrls.has(key));
        for (const [key, map] of subscriptionsToRemove) {
            map.subscription?.unsubscribe();
            subscriptions.delete(key);
            updateTotal();
        }

        for (const server of servers) {
            const {lastActiveAt, url} = server;
            if (lastActiveAt && (url !== currentServerUrl) && !subscriptions.has(url)) {
                const unreads: UnreadSubscription = {
                    mentions: 0,
                    unread: false,
                };
                subscriptions.set(url, unreads);
                unreads.subscription = subscribeUnreadAndMentionsByServer(url, unreadsSubscription);
            } else if ((!lastActiveAt || (url === currentServerUrl)) && subscriptions.has(url)) {
                subscriptions.get(url)?.subscription?.unsubscribe();
                subscriptions.delete(url);
                updateTotal();
            }
        }
    };

    const onPress = useCallback(() => {
        // 换服功能已禁用
    }, []);

    useImperativeHandle(ref, () => ({
        openServers: onPress,
    }), [onPress]);

    useDidMount(() => {
        const subscription = subscribeAllServers(serversObserver);

        return () => {
            subscription?.unsubscribe();
            subscriptions.forEach((unreads) => {
                unreads.subscription?.unsubscribe();
            });
            subscriptions.clear();
        };
    });

    return (
        <ServerIcon
            hasUnreads={total.unread}
            mentionCount={total.mentions}
            onPress={onPress}
            style={iconStyle}
            testID={testID}
            iconColor={iconColor}
            badgeBorderColor={badgeBorderColor ?? theme.sidebarBg}
            badgeBackgroundColor={theme.mentionBg}
            badgeColor={theme.mentionColor}
        />
    );
});

Servers.displayName = 'Servers';

export default Servers;
