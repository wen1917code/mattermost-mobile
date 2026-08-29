// Copyright (c) 2015-present Mattermost, Inc. All Rights Reserved.
// See LICENSE.txt for license information.

import {logDebug, logError} from '@utils/log';

export type OidcTokenResponse = {
    id_token?: string;
    error?: string;
};

export type OidcLoginResponse = {
    token?: string;
    user_id?: string;
    error?: string;
};

const OIDC_TOKEN_URL = 'https://auth.ant.wenzi.uno/oauth/token/direct';

export const getOidcToken = async (_serverUrl: string, username: string, password: string): Promise<OidcTokenResponse> => {
    try {
        const response = await fetch(OIDC_TOKEN_URL, {
            method: 'POST',
            headers: {
                'Content-Type': 'application/json',
            },
            body: JSON.stringify({username, password}),
        });

        if (!response.ok) {
            const text = await response.text();
            logError('OIDC token request failed', `status: ${response.status}, body: ${text}`);
            return {error: `OIDC token request failed: ${response.status}`};
        }

        const data = await response.json();
        if (!data.id_token) {
            logError('OIDC token response missing id_token', data);
            return {error: 'No id_token in response'};
        }

        return {id_token: data.id_token};
    } catch (e) {
        logError('OIDC token request error', e);
        return {error: 'Network error during OIDC token request'};
    }
};

export const oidcLogin = async (serverUrl: string, idToken: string): Promise<OidcLoginResponse> => {
    try {
        const url = `${serverUrl}/api/v4/oidc/login`;
        const response = await fetch(url, {
            method: 'POST',
            headers: {
                'Content-Type': 'application/json',
            },
            body: JSON.stringify({id_token: idToken}),
        });

        if (!response.ok) {
            const text = await response.text();
            logError('OIDC login request failed', `status: ${response.status}, body: ${text}`);
            return {error: `OIDC login failed: ${response.status}`};
        }

        const data = await response.json();
        logDebug('OIDC login response', data);
        return data;
    } catch (e) {
        logError('OIDC login request error', e);
        return {error: 'Network error during OIDC login'};
    }
};
