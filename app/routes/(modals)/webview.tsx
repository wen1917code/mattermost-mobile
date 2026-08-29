import FullScreenWebView from '@screens/webview';
import {useLocalSearchParams} from 'expo-router';
import React from 'react';

export default function WebViewRoute() {
    const {url} = useLocalSearchParams<{url: string}>();
    if (!url) {
        return null;
    }
    return <FullScreenWebView url={url} />;
}
