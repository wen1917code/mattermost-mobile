// Copyright (c) 2015-present Mattermost, Inc. All Rights Reserved.
// See LICENSE.txt for license information.

import React, {useMemo} from 'react';
import {Platform, StyleSheet, View} from 'react-native';
import {WebView} from 'react-native-webview';

type Props = {
    url: string;
};

const INTERNAL_UA = 'StruGGle/1.0 Mobile WebView';

// Injected into internal pages to capture JS errors and log to native
const DEBUG_BRIDGE = `
(function() {
    var _origError = window.onerror;
    window.onerror = function(msg, src, line, col, err) {
        console.warn('### WEBVIEW JS ERROR: ' + msg + ' at ' + src + ':' + line);
        if (_origError) _origError.apply(this, arguments);
    };
    var _origFetch = window.fetch;
    window.fetch = function() {
        var args = arguments;
        console.warn('### WEBVIEW FETCH: ' + args[0]);
        return _origFetch.apply(this, args).catch(function(e) {
            console.warn('### WEBVIEW FETCH ERROR: ' + args[0] + ' - ' + e.message);
            throw e;
        });
    };
    console.warn('### WEBVIEW BRIDGE LOADED');
})();
true;
`;

function isInternalUrl(url: string): boolean {
    try {
        const hostname = new URL(url).hostname;
        return hostname.endsWith('.ant.wenzi.uno') || hostname === 'ant.wenzi.uno';
    } catch {
        return false;
    }
}

const FullScreenWebView = ({url}: Props) => {
    const userAgent = useMemo(() => isInternalUrl(url) ? INTERNAL_UA : undefined, [url]);
    const internal = isInternalUrl(url);

    return (
        <View style={styles.container}>
            <WebView
                source={{uri: url}}
                style={styles.webview}
                userAgent={userAgent}
                javaScriptEnabled={true}
                domStorageEnabled={true}
                startInLoadingState={true}
                sharedCookiesEnabled={true}
                thirdPartyCookiesEnabled={true}
                setSupportMultipleWindows={false}
                onError={(syntheticEvent) => {
                    const {nativeEvent} = syntheticEvent;
                    console.warn('### WEBVIEW ERROR:', nativeEvent);
                }}
                onConsoleLog={(event) => {
                    console.warn('### WEBVIEW CONSOLE:', event.nativeEvent.message);
                }}
                injectedJavaScript={internal ? DEBUG_BRIDGE : undefined}
                onMessage={(event) => {
                    console.warn('### WEBVIEW MESSAGE:', event.nativeEvent.data);
                }}
                cacheEnabled={false}
                androidLayerType={Platform.OS === 'android' ? 'hardware' : undefined}
            />
        </View>
    );
};

const styles = StyleSheet.create({
    container: {
        flex: 1,
        backgroundColor: '#fff',
    },
    webview: {
        flex: 1,
    },
});

export default FullScreenWebView;
