// Copyright (c) 2015-present Mattermost, Inc. All Rights Reserved.
// See LICENSE.txt for license information.

import {Alert} from 'react-native';

import {storePushDisabledInServerAcknowledged} from '@actions/app/global';
import {PUSH_PROXY_RESPONSE_NOT_AVAILABLE, PUSH_PROXY_RESPONSE_UNKNOWN, PUSH_PROXY_STATUS_NOT_AVAILABLE, PUSH_PROXY_STATUS_UNKNOWN, PUSH_PROXY_STATUS_VERIFIED} from '@constants/push_proxy';
import {getPushDisabledInServerAcknowledged} from '@queries/app/global';
import EphemeralStore from '@store/ephemeral_store';

import {urlSafeBase64Encode} from './security';

import type {IntlShape} from 'react-intl';

export function pushDisabledInServerAck(serverUrl: string) {
    const extractedDomain = urlSafeBase64Encode(serverUrl);
    return getPushDisabledInServerAcknowledged(extractedDomain);
}

export async function canReceiveNotifications(serverUrl: string, _verification: string, _intl: IntlShape) {
    // FCM disabled — all push goes through WebSocket. Always mark as verified.
    EphemeralStore.setPushProxyVerificationState(serverUrl, PUSH_PROXY_STATUS_VERIFIED);
}

const handleAlertResponse = async (buttonIndex: number, serverUrl?: string) => {
    if (buttonIndex === 0 && serverUrl) {
        // User clicked "Okay" acknowledging that the push notifications are disabled on that server
        await storePushDisabledInServerAcknowledged(urlSafeBase64Encode(serverUrl));
    }
};

export function alertPushProxyError(intl: IntlShape, serverUrl?: string) {
    Alert.alert(
        intl.formatMessage({
            id: 'alert.push_proxy_error.title',
            defaultMessage: 'Notifications cannot be received from this server',
        }),
        intl.formatMessage({
            id: 'alert.push_proxy_error.description',
            defaultMessage: 'Due to the configuration of this server, notifications cannot be received in the mobile app. Contact your system admin for more information.',
        }),
        [{
            text: intl.formatMessage({id: 'alert.push_proxy.button', defaultMessage: 'Okay'}),
            onPress: () => handleAlertResponse(0, serverUrl),
        }],
    );
}

export function alertPushProxyUnknown(intl: IntlShape) {
    Alert.alert(
        intl.formatMessage({
            id: 'alert.push_proxy_unknown.title',
            defaultMessage: 'Notifications could not be received from this server',
        }),
        intl.formatMessage({
            id: 'alert.push_proxy_unknown.description',
            defaultMessage: 'This server was unable to receive push notifications for an unknown reason. This will be attempted again next time you connect.',
        }),
        [{
            text: intl.formatMessage({id: 'alert.push_proxy.button', defaultMessage: 'Okay'}),
        }],
    );
}
