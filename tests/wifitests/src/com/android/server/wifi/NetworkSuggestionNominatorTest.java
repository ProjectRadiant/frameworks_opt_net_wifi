/*
 * Copyright (C) 2018 The Android Open Source Project
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.android.server.wifi;

import static android.net.wifi.WifiConfiguration.INVALID_NETWORK_ID;
import static android.net.wifi.WifiConfiguration.NetworkSelectionStatus
        .NETWORK_SELECTION_TEMPORARY_DISABLED;

import static com.android.server.wifi.WifiConfigurationTestUtil.SECURITY_PSK;
import static com.android.server.wifi.WifiConfigurationTestUtil.generateWifiConfig;

import static org.junit.Assert.*;
import static org.mockito.Mockito.*;

import android.net.wifi.WifiConfiguration;
import android.net.wifi.WifiNetworkSuggestion;
import android.net.wifi.WifiSsid;
import android.util.LocalLog;
import android.util.Pair;

import androidx.test.filters.SmallTest;

import com.android.server.wifi.WifiNetworkSuggestionsManager.ExtendedWifiNetworkSuggestion;
import com.android.server.wifi.WifiNetworkSuggestionsManager.PerAppInfo;
import com.android.server.wifi.hotspot2.PasspointNetworkNominateHelper;

import org.junit.Before;
import org.junit.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.ArgumentMatcher;
import org.mockito.Mock;
import org.mockito.MockitoAnnotations;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * Unit tests for {@link NetworkSuggestionNominator}.
 */
@SmallTest
public class NetworkSuggestionNominatorTest extends WifiBaseTest {
    private static final int TEST_UID = 3555;
    private static final int TEST_UID_OTHER = 3545;
    private static final int TEST_NETWORK_ID = 55;
    private static final String TEST_PACKAGE = "com.test";
    private static final String TEST_PACKAGE_OTHER = "com.test.other";
    private static final String TEST_FQDN = "fqdn";

    private @Mock WifiConfigManager mWifiConfigManager;
    private @Mock WifiNetworkSuggestionsManager mWifiNetworkSuggestionsManager;
    private @Mock PasspointNetworkNominateHelper mPasspointNetworkNominateHelper;
    private @Mock Clock mClock;
    private NetworkSuggestionNominator mNetworkSuggestionNominator;

    /** Sets up test. */
    @Before
    public void setUp() throws Exception {
        MockitoAnnotations.initMocks(this);
        mNetworkSuggestionNominator = new NetworkSuggestionNominator(
                mWifiNetworkSuggestionsManager, mWifiConfigManager, mPasspointNetworkNominateHelper,
                new LocalLog(100));
    }

    /**
     * Ensure that we ignore all scan results not matching the network suggestion.
     * Expected connectable Networks: {}
     */
    @Test
    public void testSelectNetworkSuggestionForNoMatch() {
        String[] scanSsids = {"test1", "test2"};
        String[] bssids = {"6c:f3:7f:ae:8c:f3", "6c:f3:7f:ae:8c:f4"};
        int[] freqs = {2470, 2437};
        String[] caps = {"[WPA2-EAP-CCMP][ESS]", "[WPA2-EAP-CCMP][ESS]"};
        int[] levels = {-67, -76};
        String[] suggestionSsids = {};
        int[] securities = {};
        boolean[] appInteractions = {};
        boolean[] meteredness = {};
        int[] priorities = {};
        int[] uids = {};
        String[] packageNames = {};

        ScanDetail[] scanDetails =
                buildScanDetails(scanSsids, bssids, freqs, caps, levels, mClock);
        ExtendedWifiNetworkSuggestion[] suggestions = buildNetworkSuggestions(suggestionSsids,
                securities, appInteractions, meteredness, priorities, uids, packageNames);
        // Link the scan result with suggestions.
        linkScanDetailsWithNetworkSuggestions(scanDetails, suggestions);

        List<Pair<ScanDetail, WifiConfiguration>> connectableNetworks = new ArrayList<>();
        mNetworkSuggestionNominator.nominateNetworks(
                Arrays.asList(scanDetails), null, null, true, false,
                (ScanDetail scanDetail, WifiConfiguration configuration) -> {
                    connectableNetworks.add(Pair.create(scanDetail, configuration));
                });

        assertTrue(connectableNetworks.isEmpty());
    }

    /**
     * Ensure that we nominate the only matching network suggestion.
     * Expected connectable Networks: {suggestionSsids[0]}
     */
    @Test
    public void testSelectNetworkSuggestionForOneMatch() {
        String[] scanSsids = {"test1", "test2"};
        String[] bssids = {"6c:f3:7f:ae:8c:f3", "6c:f3:7f:ae:8c:f4"};
        int[] freqs = {2470, 2437};
        String[] caps = {"[WPA2-EAP-CCMP][ESS]", "[WPA2-EAP-CCMP][ESS]"};
        int[] levels = {-67, -76};
        String[] suggestionSsids = {"\"" + scanSsids[0] + "\""};
        int[] securities = {SECURITY_PSK};
        boolean[] appInteractions = {true};
        boolean[] meteredness = {true};
        int[] priorities = {-1};
        int[] uids = {TEST_UID};
        String[] packageNames = {TEST_PACKAGE};

        ScanDetail[] scanDetails =
                buildScanDetails(scanSsids, bssids, freqs, caps, levels, mClock);
        ExtendedWifiNetworkSuggestion[] suggestions = buildNetworkSuggestions(suggestionSsids,
                securities, appInteractions, meteredness, priorities, uids, packageNames);
        // Link the scan result with suggestions.
        linkScanDetailsWithNetworkSuggestions(scanDetails, suggestions);
        // setup config manager interactions.
        setupAddToWifiConfigManager(suggestions[0].wns.wifiConfiguration);

        List<Pair<ScanDetail, WifiConfiguration>> connectableNetworks = new ArrayList<>();
        mNetworkSuggestionNominator.nominateNetworks(
                Arrays.asList(scanDetails), null, null, true, false,
                (ScanDetail scanDetail, WifiConfiguration configuration) -> {
                    connectableNetworks.add(Pair.create(scanDetail, configuration));
                });


        validateConnectableNetworks(connectableNetworks, scanSsids[0]);

        verifyAddToWifiConfigManager(suggestions[0].wns.wifiConfiguration);
    }

    /**
     * Ensure that we nominate the all network suggestion corresponding to the scan results
     * Expected connectable Networks: {suggestionSsids[0], suggestionSsids[1]}
     */
    @Test
    public void testSelectNetworkSuggestionForMultipleMatch() {
        String[] scanSsids = {"test1", "test2"};
        String[] bssids = {"6c:f3:7f:ae:8c:f3", "6c:f3:7f:ae:8c:f4"};
        int[] freqs = {2470, 2437};
        String[] caps = {"[WPA2-EAP-CCMP][ESS]", "[WPA2-EAP-CCMP][ESS]"};
        int[] levels = {-56, -45};
        String[] suggestionSsids = {"\"" + scanSsids[0] + "\"", "\"" + scanSsids[1] + "\""};
        int[] securities = {SECURITY_PSK, SECURITY_PSK};
        boolean[] appInteractions = {true, true};
        boolean[] meteredness = {true, true};
        int[] priorities = {-1, -1};
        int[] uids = {TEST_UID, TEST_UID};
        String[] packageNames = {TEST_PACKAGE, TEST_PACKAGE};

        ScanDetail[] scanDetails =
                buildScanDetails(scanSsids, bssids, freqs, caps, levels, mClock);
        ExtendedWifiNetworkSuggestion[] suggestions = buildNetworkSuggestions(suggestionSsids,
                securities, appInteractions, meteredness, priorities, uids, packageNames);
        // Link the scan result with suggestions.
        linkScanDetailsWithNetworkSuggestions(scanDetails, suggestions);
        // setup config manager interactions.
        setupAddToWifiConfigManager(suggestions[0].wns.wifiConfiguration,
                suggestions[1].wns.wifiConfiguration);

        List<Pair<ScanDetail, WifiConfiguration>> connectableNetworks = new ArrayList<>();
        mNetworkSuggestionNominator.nominateNetworks(
                Arrays.asList(scanDetails), null, null, true, false,
                (ScanDetail scanDetail, WifiConfiguration configuration) -> {
                    connectableNetworks.add(Pair.create(scanDetail, configuration));
                });

        validateConnectableNetworks(connectableNetworks, scanSsids[0], scanSsids[1]);

        verifyAddToWifiConfigManager(suggestions[1].wns.wifiConfiguration,
                suggestions[1].wns.wifiConfiguration);
    }

    /**
     * Ensure that we nominate the network suggestion corresponding to the scan result with
     * higest priority.
     * Expected connectable Networks: {suggestionSsids[0]}
     */
    @Test
    public void testSelectNetworkSuggestionForMultipleMatchHighPriorityWins() {
        String[] scanSsids = {"test1", "test2"};
        String[] bssids = {"6c:f3:7f:ae:8c:f3", "6c:f3:7f:ae:8c:f4"};
        int[] freqs = {2470, 2437};
        String[] caps = {"[WPA2-EAP-CCMP][ESS]", "[WPA2-EAP-CCMP][ESS]"};
        int[] levels = {-56, -45};
        String[] suggestionSsids = {"\"" + scanSsids[0] + "\"", "\"" + scanSsids[1] + "\""};
        int[] securities = {SECURITY_PSK, SECURITY_PSK};
        boolean[] appInteractions = {true, true};
        boolean[] meteredness = {true, true};
        int[] priorities = {5, 1};
        int[] uids = {TEST_UID, TEST_UID};
        String[] packageNames = {TEST_PACKAGE, TEST_PACKAGE};

        ScanDetail[] scanDetails =
                buildScanDetails(scanSsids, bssids, freqs, caps, levels, mClock);
        ExtendedWifiNetworkSuggestion[] suggestions = buildNetworkSuggestions(suggestionSsids,
                securities, appInteractions, meteredness, priorities, uids, packageNames);
        // Link the scan result with suggestions.
        linkScanDetailsWithNetworkSuggestions(scanDetails, suggestions);
        // setup config manager interactions.
        setupAddToWifiConfigManager(suggestions[0].wns.wifiConfiguration,
                suggestions[1].wns.wifiConfiguration);

        List<Pair<ScanDetail, WifiConfiguration>> connectableNetworks = new ArrayList<>();
        mNetworkSuggestionNominator.nominateNetworks(
                Arrays.asList(scanDetails), null, null, true, false,
                (ScanDetail scanDetail, WifiConfiguration configuration) -> {
                    connectableNetworks.add(Pair.create(scanDetail, configuration));
                });

        validateConnectableNetworks(connectableNetworks, scanSsids[0]);

        verifyAddToWifiConfigManager(suggestions[0].wns.wifiConfiguration);
    }

    /**
     * Ensure that we nominate one network when multiple suggestor suggested same network.
     *
     * Expected connectable Networks: {suggestionSsids[0],
     *                                 (suggestionSsids[1] || suggestionSsids[2]}
     */
    @Test
    public void testSelectNetworkSuggestionForMultipleMatchWithMultipleSuggestions() {
        String[] scanSsids = {"test1", "test2"};
        String[] bssids = {"6c:f3:7f:ae:8c:f3", "6c:f3:7f:ae:8c:f4"};
        int[] freqs = {2470, 2437};
        String[] caps = {"[WPA2-EAP-CCMP][ESS]", "[WPA2-EAP-CCMP][ESS]"};
        int[] levels = {-23, -45};
        String[] suggestionSsids = {"\"" + scanSsids[0] + "\"", "\"" + scanSsids[1] + "\"",
                "\"" + scanSsids[1] + "\""};
        int[] securities = {SECURITY_PSK, SECURITY_PSK, SECURITY_PSK};
        boolean[] appInteractions = {true, true, false};
        boolean[] meteredness = {true, true, false};
        int[] priorities = {-1, -1, -1};
        int[] uids = {TEST_UID, TEST_UID, TEST_UID_OTHER};
        String[] packageNames = {TEST_PACKAGE, TEST_PACKAGE, TEST_PACKAGE_OTHER};

        ScanDetail[] scanDetails =
                buildScanDetails(scanSsids, bssids, freqs, caps, levels, mClock);
        ExtendedWifiNetworkSuggestion[] suggestions = buildNetworkSuggestions(suggestionSsids,
                securities, appInteractions, meteredness, priorities, uids, packageNames);
        // Link the scan result with suggestions.
        linkScanDetailsWithNetworkSuggestions(scanDetails, suggestions);
        // setup config manager interactions.
        setupAddToWifiConfigManager(suggestions[0].wns.wifiConfiguration,
                suggestions[1].wns.wifiConfiguration, suggestions[2].wns.wifiConfiguration);

        List<Pair<ScanDetail, WifiConfiguration>> connectableNetworks = new ArrayList<>();
        mNetworkSuggestionNominator.nominateNetworks(
                Arrays.asList(scanDetails), null, null, true, false,
                (ScanDetail scanDetail, WifiConfiguration configuration) -> {
                    connectableNetworks.add(Pair.create(scanDetail, configuration));
                });

        validateConnectableNetworks(connectableNetworks, scanSsids);

        verifyAddToWifiConfigManager(suggestions[0].wns.wifiConfiguration,
                suggestions[1].wns.wifiConfiguration);
    }

    /**
     * Ensure that we nominate the network suggestion with the higest priority among network
     * suggestions from the same package. Among different packages, nominate all the suggestion
     * corresponding to the scan result.
     *
     * The suggestion[1] has higher priority than suggestion[0].
     *
     * Expected connectable Networks: {suggestionSsids[1],
     *                                 (suggestionSsids[2],
     *                                  suggestionSsids[3]}
     */
    @Test
    public void
            testSelectNetworkSuggestionForMultipleMatchWithMultipleSuggestionsHighPriorityWins() {
        String[] scanSsids = {"test1", "test2", "test3", "test4"};
        String[] bssids = {"6c:f3:7f:ae:8c:f3", "6c:f3:7f:ae:8c:f4", "6c:fc:de:34:12",
                "6c:fd:a1:11:11:98"};
        int[] freqs = {2470, 2437, 2470, 2437};
        String[] caps = {"[WPA2-EAP-CCMP][ESS]", "[WPA2-EAP-CCMP][ESS]", "[WPA2-EAP-CCMP][ESS]",
                "[WPA2-EAP-CCMP][ESS]"};
        int[] levels = {-23, -45, -56, -65};
        String[] suggestionSsids = {"\"" + scanSsids[0] + "\"", "\"" + scanSsids[1] + "\"",
                "\"" + scanSsids[2] + "\"", "\"" + scanSsids[3] + "\""};
        int[] securities = {SECURITY_PSK, SECURITY_PSK, SECURITY_PSK, SECURITY_PSK};
        boolean[] appInteractions = {true, true, false, false};
        boolean[] meteredness = {true, true, false, false};
        int[] priorities = {0, 5, -1, -1};
        int[] uids = {TEST_UID, TEST_UID, TEST_UID_OTHER, TEST_UID_OTHER};
        String[] packageNames = {TEST_PACKAGE, TEST_PACKAGE, TEST_PACKAGE_OTHER,
                TEST_PACKAGE_OTHER};

        ScanDetail[] scanDetails =
                buildScanDetails(scanSsids, bssids, freqs, caps, levels, mClock);
        ExtendedWifiNetworkSuggestion[] suggestions = buildNetworkSuggestions(suggestionSsids,
                securities, appInteractions, meteredness, priorities, uids, packageNames);
        // Link the scan result with suggestions.
        linkScanDetailsWithNetworkSuggestions(scanDetails, suggestions);
        // setup config manager interactions.
        setupAddToWifiConfigManager(suggestions[0].wns.wifiConfiguration,
                suggestions[1].wns.wifiConfiguration, suggestions[2].wns.wifiConfiguration,
                suggestions[3].wns.wifiConfiguration);

        List<Pair<ScanDetail, WifiConfiguration>> connectableNetworks = new ArrayList<>();
        mNetworkSuggestionNominator.nominateNetworks(
                Arrays.asList(scanDetails), null, null, true, false,
                (ScanDetail scanDetail, WifiConfiguration configuration) -> {
                    connectableNetworks.add(Pair.create(scanDetail, configuration));
                });

        validateConnectableNetworks(connectableNetworks, scanSsids[1], scanSsids[2], scanSsids[3]);

        verifyAddToWifiConfigManager(suggestions[1].wns.wifiConfiguration,
                suggestions[2].wns.wifiConfiguration, suggestions[3].wns.wifiConfiguration);
    }

    /**
     * Ensure that we nominate no candidate if the only matching network suggestion, but we failed
     * the {@link WifiConfigManager} interactions.
     *
     * Expected connectable Networks: {}
     */
    @Test
    public void testSelectNetworkSuggestionForOneMatchButFailToAddToWifiConfigManager() {
        String[] scanSsids = {"test1", "test2"};
        String[] bssids = {"6c:f3:7f:ae:8c:f3", "6c:f3:7f:ae:8c:f4"};
        int[] freqs = {2470, 2437};
        String[] caps = {"[WPA2-EAP-CCMP][ESS]", "[WPA2-EAP-CCMP][ESS]"};
        int[] levels = {-67, -76};
        String[] suggestionSsids = {"\"" + scanSsids[0] + "\""};
        int[] securities = {SECURITY_PSK};
        boolean[] appInteractions = {true};
        boolean[] meteredness = {true};
        int[] priorities = {-1};
        int[] uids = {TEST_UID};
        String[] packageNames = {TEST_PACKAGE};

        ScanDetail[] scanDetails =
                buildScanDetails(scanSsids, bssids, freqs, caps, levels, mClock);
        ExtendedWifiNetworkSuggestion[] suggestions = buildNetworkSuggestions(suggestionSsids,
                securities, appInteractions, meteredness, priorities, uids, packageNames);
        // Link the scan result with suggestions.
        linkScanDetailsWithNetworkSuggestions(scanDetails, suggestions);
        // Fail add to WifiConfigManager
        when(mWifiConfigManager.addOrUpdateNetwork(any(), anyInt(), anyString()))
                .thenReturn(new NetworkUpdateResult(INVALID_NETWORK_ID));

        List<Pair<ScanDetail, WifiConfiguration>> connectableNetworks = new ArrayList<>();
        mNetworkSuggestionNominator.nominateNetworks(
                Arrays.asList(scanDetails), null, null, true, false,
                (ScanDetail scanDetail, WifiConfiguration configuration) -> {
                    connectableNetworks.add(Pair.create(scanDetail, configuration));
                });

        assertTrue(connectableNetworks.isEmpty());

        verify(mWifiConfigManager, times(scanSsids.length))
                .wasEphemeralNetworkDeleted(anyString());
        verify(mWifiConfigManager).getConfiguredNetwork(eq(
                suggestions[0].wns.wifiConfiguration.getKey()));
        verify(mWifiConfigManager).addOrUpdateNetwork(any(), anyInt(), anyString());
        // Verify we did not try to add any new networks or other interactions with
        // WifiConfigManager.
        verifyNoMoreInteractions(mWifiConfigManager);
    }

    /**
     * Ensure that we nominate the only matching network suggestion, but that matches an existing
     * saved network (maybe saved or maybe it exists from a previous connection attempt) .
     *
     * Expected connectable Networks: {suggestionSsids[0]}
     */
    @Test
    public void testSelectNetworkSuggestionForOneMatchForExistingNetwork() {
        String[] scanSsids = {"test1", "test2"};
        String[] bssids = {"6c:f3:7f:ae:8c:f3", "6c:f3:7f:ae:8c:f4"};
        int[] freqs = {2470, 2437};
        String[] caps = {"[WPA2-EAP-CCMP][ESS]", "[WPA2-EAP-CCMP][ESS]"};
        int[] levels = {-67, -76};
        String[] suggestionSsids = {"\"" + scanSsids[0] + "\""};
        int[] securities = {SECURITY_PSK};
        boolean[] appInteractions = {true};
        boolean[] meteredness = {true};
        int[] priorities = {-1};
        int[] uids = {TEST_UID};
        String[] packageNames = {TEST_PACKAGE};

        ScanDetail[] scanDetails =
                buildScanDetails(scanSsids, bssids, freqs, caps, levels, mClock);
        ExtendedWifiNetworkSuggestion[] suggestions = buildNetworkSuggestions(suggestionSsids,
                securities, appInteractions, meteredness, priorities, uids, packageNames);
        // Link the scan result with suggestions.
        linkScanDetailsWithNetworkSuggestions(scanDetails, suggestions);
        // setup config manager interactions.
        suggestions[0].wns.wifiConfiguration.fromWifiNetworkSuggestion = true;
        suggestions[0].wns.wifiConfiguration.ephemeral = true;
        setupAddToWifiConfigManager(suggestions[0].wns.wifiConfiguration);
        // Existing saved network matching the credentials.
        when(mWifiConfigManager.getConfiguredNetwork(suggestions[0].wns.wifiConfiguration.getKey()))
                .thenReturn(suggestions[0].wns.wifiConfiguration);

        List<Pair<ScanDetail, WifiConfiguration>> connectableNetworks = new ArrayList<>();
        mNetworkSuggestionNominator.nominateNetworks(
                Arrays.asList(scanDetails), null, null, true, false,
                (ScanDetail scanDetail, WifiConfiguration configuration) -> {
                    connectableNetworks.add(Pair.create(scanDetail, configuration));
                });

        validateConnectableNetworks(connectableNetworks, new String[] {scanSsids[0]});

        // check for any saved networks.
        verify(mWifiConfigManager, times(scanSsids.length))
                .wasEphemeralNetworkDeleted(anyString());
        verify(mWifiConfigManager)
                .getConfiguredNetwork(suggestions[0].wns.wifiConfiguration.getKey());
        verify(mWifiConfigManager).addOrUpdateNetwork(eq(suggestions[0].wns.wifiConfiguration),
                eq(suggestions[0].perAppInfo.uid), eq(suggestions[0].perAppInfo.packageName));
        verify(mWifiConfigManager).getConfiguredNetwork(
                suggestions[0].wns.wifiConfiguration.networkId);
        // Verify we did not try to add any new networks or other interactions with
        // WifiConfigManager.
        verifyNoMoreInteractions(mWifiConfigManager);
    }

    /**
     * Ensure that we don't nominate the only matching network suggestion if it was previously
     * disabled by the user.
     *
     * Expected connectable Networks: {}
     */
    @Test
    public void testSelectNetworkSuggestionForOneMatchButUserForgotTheNetwork() {
        String[] scanSsids = {"test1", "test2"};
        String[] bssids = {"6c:f3:7f:ae:8c:f3", "6c:f3:7f:ae:8c:f4"};
        int[] freqs = {2470, 2437};
        String[] caps = {"[WPA2-EAP-CCMP][ESS]", "[WPA2-EAP-CCMP][ESS]"};
        int[] levels = {-67, -76};
        String[] suggestionSsids = {"\"" + scanSsids[0] + "\""};
        int[] securities = {SECURITY_PSK};
        boolean[] appInteractions = {true};
        boolean[] meteredness = {true};
        int[] priorities = {-1};
        int[] uids = {TEST_UID};
        String[] packageNames = {TEST_PACKAGE};

        ScanDetail[] scanDetails =
                buildScanDetails(scanSsids, bssids, freqs, caps, levels, mClock);
        ExtendedWifiNetworkSuggestion[] suggestions = buildNetworkSuggestions(suggestionSsids,
                securities, appInteractions, meteredness, priorities, uids, packageNames);
        // Link the scan result with suggestions.
        linkScanDetailsWithNetworkSuggestions(scanDetails, suggestions);
        // setup config manager interactions.
        setupAddToWifiConfigManager(suggestions[0].wns.wifiConfiguration);
        // Network was disabled by the user.
        when(mWifiConfigManager.wasEphemeralNetworkDeleted(suggestionSsids[0])).thenReturn(true);

        List<Pair<ScanDetail, WifiConfiguration>> connectableNetworks = new ArrayList<>();
        mNetworkSuggestionNominator.nominateNetworks(
                Arrays.asList(scanDetails), null, null, true, false,
                (ScanDetail scanDetail, WifiConfiguration configuration) ->
                        connectableNetworks.add(Pair.create(scanDetail, configuration)));

        assertTrue(connectableNetworks.isEmpty());

        verify(mWifiConfigManager, times(scanSsids.length)).wasEphemeralNetworkDeleted(anyString());
        // Verify we did not try to add any new networks or other interactions with
        // WifiConfigManager.
        verifyNoMoreInteractions(mWifiConfigManager);
    }

    /**
     * Ensure that we don't nominate the only matching network suggestion if the network
     * configuration already exists (maybe saved or maybe it exists from a previous connection
     * attempt) and blacklisted.
     *
     * Expected connectable Networks: {}
     */
    @Test
    public void testSelectNetworkSuggestionForOneMatchForExistingNetworkButTempDisabled() {
        String[] scanSsids = {"test1", "test2"};
        String[] bssids = {"6c:f3:7f:ae:8c:f3", "6c:f3:7f:ae:8c:f4"};
        int[] freqs = {2470, 2437};
        String[] caps = {"[WPA2-EAP-CCMP][ESS]", "[WPA2-EAP-CCMP][ESS]"};
        int[] levels = {-67, -76};
        String[] suggestionSsids = {"\"" + scanSsids[0] + "\""};
        int[] securities = {SECURITY_PSK};
        boolean[] appInteractions = {true};
        boolean[] meteredness = {true};
        int[] priorities = {-1};
        int[] uids = {TEST_UID};
        String[] packageNames = {TEST_PACKAGE};

        ScanDetail[] scanDetails =
                buildScanDetails(scanSsids, bssids, freqs, caps, levels, mClock);
        ExtendedWifiNetworkSuggestion[] suggestions = buildNetworkSuggestions(suggestionSsids,
                securities, appInteractions, meteredness, priorities, uids, packageNames);
        // Link the scan result with suggestions.
        linkScanDetailsWithNetworkSuggestions(scanDetails, suggestions);
        // setup config manager interactions.
        suggestions[0].wns.wifiConfiguration.fromWifiNetworkSuggestion = true;
        suggestions[0].wns.wifiConfiguration.ephemeral = true;
        setupAddToWifiConfigManager(suggestions[0].wns.wifiConfiguration);
        // Mark the network disabled.
        suggestions[0].wns.wifiConfiguration.getNetworkSelectionStatus().setNetworkSelectionStatus(
                NETWORK_SELECTION_TEMPORARY_DISABLED);
        // Existing network matching the credentials.
        when(mWifiConfigManager.getConfiguredNetwork(suggestions[0].wns.wifiConfiguration.getKey()))
                .thenReturn(suggestions[0].wns.wifiConfiguration);

        List<Pair<ScanDetail, WifiConfiguration>> connectableNetworks = new ArrayList<>();
        mNetworkSuggestionNominator.nominateNetworks(
                Arrays.asList(scanDetails), null, null, true, false,
                (ScanDetail scanDetail, WifiConfiguration configuration) -> {
                    connectableNetworks.add(Pair.create(scanDetail, configuration));
                });

        assertTrue(connectableNetworks.isEmpty());

        verify(mWifiConfigManager, times(scanSsids.length))
                .wasEphemeralNetworkDeleted(anyString());
        verify(mWifiConfigManager).getConfiguredNetwork(eq(
                suggestions[0].wns.wifiConfiguration.getKey()));
        verify(mWifiConfigManager).addOrUpdateNetwork(eq(suggestions[0].wns.wifiConfiguration),
                eq(suggestions[0].perAppInfo.uid), eq(suggestions[0].perAppInfo.packageName));
        verify(mWifiConfigManager).getConfiguredNetwork(
                suggestions[0].wns.wifiConfiguration.networkId);
        verify(mWifiConfigManager).tryEnableNetwork(eq(
                suggestions[0].wns.wifiConfiguration.networkId));
        // Verify we did not try to add any new networks or other interactions with
        // WifiConfigManager.
        verifyNoMoreInteractions(mWifiConfigManager);
    }

    /**
     * Ensure that we do nominate the only matching network suggestion if the network configuration
     * already exists (maybe saved or maybe it exists from a previous connection attempt) and a
     * temporary blacklist expired.
     *
     * Expected connectable Networks: {suggestionSsids[0]}
     */
    @Test
    public void testSelectNetworkSuggestionForOneMatchForExistingNetworkButTempDisableExpired() {
        String[] scanSsids = {"test1", "test2"};
        String[] bssids = {"6c:f3:7f:ae:8c:f3", "6c:f3:7f:ae:8c:f4"};
        int[] freqs = {2470, 2437};
        String[] caps = {"[WPA2-EAP-CCMP][ESS]", "[WPA2-EAP-CCMP][ESS]"};
        int[] levels = {-67, -76};
        String[] suggestionSsids = {"\"" + scanSsids[0] + "\""};
        int[] securities = {SECURITY_PSK};
        boolean[] appInteractions = {true};
        boolean[] meteredness = {true};
        int[] priorities = {-1};
        int[] uids = {TEST_UID};
        String[] packageNames = {TEST_PACKAGE};

        ScanDetail[] scanDetails =
                buildScanDetails(scanSsids, bssids, freqs, caps, levels, mClock);
        ExtendedWifiNetworkSuggestion[] suggestions = buildNetworkSuggestions(suggestionSsids,
                securities, appInteractions, meteredness, priorities, uids, packageNames);
        // Link the scan result with suggestions.
        linkScanDetailsWithNetworkSuggestions(scanDetails, suggestions);
        // setup config manager interactions.
        suggestions[0].wns.wifiConfiguration.fromWifiNetworkSuggestion = true;
        suggestions[0].wns.wifiConfiguration.ephemeral = true;
        setupAddToWifiConfigManager(suggestions[0].wns.wifiConfiguration);
        // Mark the network disabled.
        suggestions[0].wns.wifiConfiguration.getNetworkSelectionStatus().setNetworkSelectionStatus(
                NETWORK_SELECTION_TEMPORARY_DISABLED);
        // Existing network matching the credentials.
        when(mWifiConfigManager.getConfiguredNetwork(suggestions[0].wns.wifiConfiguration.getKey()))
                .thenReturn(suggestions[0].wns.wifiConfiguration);
        when(mWifiConfigManager.tryEnableNetwork(suggestions[0].wns.wifiConfiguration.networkId))
                .thenReturn(true);

        List<Pair<ScanDetail, WifiConfiguration>> connectableNetworks = new ArrayList<>();
        mNetworkSuggestionNominator.nominateNetworks(
                Arrays.asList(scanDetails), null, null, true, false,
                (ScanDetail scanDetail, WifiConfiguration configuration) -> {
                    connectableNetworks.add(Pair.create(scanDetail, configuration));
                });

        validateConnectableNetworks(connectableNetworks, new String[] {scanSsids[0]});

        verify(mWifiConfigManager, times(scanSsids.length))
                .wasEphemeralNetworkDeleted(anyString());
        verify(mWifiConfigManager).getConfiguredNetwork(eq(
                suggestions[0].wns.wifiConfiguration.getKey()));
        verify(mWifiConfigManager).addOrUpdateNetwork(eq(suggestions[0].wns.wifiConfiguration),
                eq(suggestions[0].perAppInfo.uid), eq(suggestions[0].perAppInfo.packageName));
        verify(mWifiConfigManager).getConfiguredNetwork(
                suggestions[0].wns.wifiConfiguration.networkId);
        verify(mWifiConfigManager).tryEnableNetwork(eq(
                suggestions[0].wns.wifiConfiguration.networkId));
        // Verify we did not try to add any new networks or other interactions with
        // WifiConfigManager.
        verifyNoMoreInteractions(mWifiConfigManager);
    }

    /**
     * Ensure that we do nominate the only matching passponit network suggestion.
     * Expected connectable Networks: {suggestionSsids[0]}
     */
    @Test
    public void testSuggestionPasspointNetworkCandidatesMatches() {
        String[] scanSsids = {"test1", "test2"};
        String[] bssids = {"6c:f3:7f:ae:8c:f3", "6c:f3:7f:ae:8c:f4"};
        int[] freqs = {2470, 2437};
        String[] caps = {"[WPA2-EAP-CCMP][ESS]", "[WPA2-EAP-CCMP][ESS]"};
        int[] levels = {-67, -76};
        String[] suggestionSsids = {"\"" + scanSsids[0] + "\""};
        int[] securities = {SECURITY_PSK};
        boolean[] appInteractions = {true};
        boolean[] meteredness = {true};
        int[] priorities = {-1};
        int[] uids = {TEST_UID};
        String[] packageNames = {TEST_PACKAGE};
        ScanDetail[] scanDetails =
                buildScanDetails(scanSsids, bssids, freqs, caps, levels, mClock);
        ExtendedWifiNetworkSuggestion[] suggestions = buildNetworkSuggestions(suggestionSsids,
                securities, appInteractions, meteredness, priorities, uids, packageNames);
        HashSet<ExtendedWifiNetworkSuggestion> matchedExtSuggestions = new HashSet<>();
        matchedExtSuggestions.add(suggestions[0]);
        List<Pair<ScanDetail, WifiConfiguration>> passpointCandidates = new ArrayList<>();
        suggestions[0].wns.wifiConfiguration.FQDN = TEST_FQDN;
        passpointCandidates.add(Pair.create(scanDetails[0], suggestions[0].wns.wifiConfiguration));
        when(mPasspointNetworkNominateHelper
                .getPasspointNetworkCandidates(Arrays.asList(scanDetails), true))
                .thenReturn(passpointCandidates);
        when(mWifiNetworkSuggestionsManager.getNetworkSuggestionsForFqfn(TEST_FQDN))
                .thenReturn(matchedExtSuggestions);
        List<Pair<ScanDetail, WifiConfiguration>> connectableNetworks = new ArrayList<>();
        mNetworkSuggestionNominator.nominateNetworks(
                Arrays.asList(scanDetails), null, null, true, false,
                (ScanDetail scanDetail, WifiConfiguration configuration) -> {
                    connectableNetworks.add(Pair.create(scanDetail, configuration));
                });
        assertEquals(1, connectableNetworks.size());
        validateConnectableNetworks(connectableNetworks, new String[] {scanSsids[0]});
    }

    private void setupAddToWifiConfigManager(WifiConfiguration...candidates) {
        for (int i = 0; i < candidates.length; i++) {
            WifiConfiguration candidate = candidates[i];
            // setup & verify the WifiConfigmanager interactions for adding/enabling the network.
            when(mWifiConfigManager.addOrUpdateNetwork(
                    argThat(new WifiConfigMatcher(candidate)), anyInt(), anyString()))
                    .thenReturn(new NetworkUpdateResult(TEST_NETWORK_ID + i));
            when(mWifiConfigManager.updateNetworkSelectionStatus(eq(TEST_NETWORK_ID + i), anyInt()))
                    .thenReturn(true);
            candidate.networkId = TEST_NETWORK_ID + i;
            when(mWifiConfigManager.getConfiguredNetwork(TEST_NETWORK_ID + i))
                    .thenReturn(candidate);
        }
    }

    class WifiConfigMatcher implements ArgumentMatcher<WifiConfiguration> {
        private final WifiConfiguration mConfig;

        WifiConfigMatcher(WifiConfiguration config) {
            assertNotNull(config);
            mConfig = config;
        }

        @Override
        public boolean matches(WifiConfiguration otherConfig) {
            if (otherConfig == null) return false;
            return mConfig.getKey().equals(otherConfig.getKey());
        }
    }

    private void verifyAddToWifiConfigManager(WifiConfiguration...candidates) {
        // check for any saved networks.
        verify(mWifiConfigManager, atLeast(candidates.length)).getConfiguredNetwork(anyString());

        ArgumentCaptor<WifiConfiguration> wifiConfigurationCaptor =
                ArgumentCaptor.forClass(WifiConfiguration.class);
        verify(mWifiConfigManager, times(candidates.length)).addOrUpdateNetwork(
                wifiConfigurationCaptor.capture(), anyInt(), anyString());
        for (int i = 0; i < candidates.length; i++) {
            WifiConfiguration addedWifiConfiguration = null;
            for (WifiConfiguration configuration : wifiConfigurationCaptor.getAllValues()) {
                if (configuration.SSID.equals(candidates[i].SSID)) {
                    addedWifiConfiguration = configuration;
                    break;
                }
            }
            assertNotNull(addedWifiConfiguration);
            assertTrue(addedWifiConfiguration.ephemeral);
            assertTrue(addedWifiConfiguration.fromWifiNetworkSuggestion);
        }

        verify(mWifiConfigManager, times(candidates.length)).updateNetworkSelectionStatus(
                anyInt(), anyInt());
        verify(mWifiConfigManager, times(candidates.length)).getConfiguredNetwork(anyInt());
    }

    /**
     * Build an array of scanDetails based on the caller supplied network SSID, BSSID,
     * frequency, capability and RSSI level information.
     */
    public static ScanDetail[] buildScanDetails(String[] ssids, String[] bssids, int[] freqs,
                                                    String[] caps, int[] levels, Clock clock) {
        if (ssids == null || ssids.length == 0) return new ScanDetail[0];

        ScanDetail[] scanDetails = new ScanDetail[ssids.length];
        long timeStamp = clock.getElapsedSinceBootMillis();
        for (int index = 0; index < ssids.length; index++) {
            scanDetails[index] = new ScanDetail(WifiSsid.createFromAsciiEncoded(ssids[index]),
                    bssids[index], caps[index], levels[index], freqs[index], timeStamp, 0);
        }
        return scanDetails;
    }

    /**
     * Generate an array of {@link android.net.wifi.WifiConfiguration} based on the caller
     * supplied network SSID and security information.
     */
    public static WifiConfiguration[] buildWifiConfigurations(String[] ssids, int[] securities) {
        if (ssids == null || ssids.length == 0) return new WifiConfiguration[0];

        WifiConfiguration[] configs = new WifiConfiguration[ssids.length];
        for (int index = 0; index < ssids.length; index++) {
            configs[index] = generateWifiConfig(-1, 0, ssids[index], false, true, null,
                    null, securities[index]);
        }
        return configs;
    }

    private ExtendedWifiNetworkSuggestion[] buildNetworkSuggestions(
            String[] ssids, int[] securities, boolean[] appInteractions, boolean[] meteredness,
            int[] priorities, int[] uids, String[] packageNames) {
        WifiConfiguration[] configs = buildWifiConfigurations(ssids, securities);
        ExtendedWifiNetworkSuggestion[] suggestions =
                new ExtendedWifiNetworkSuggestion[configs.length];
        for (int i = 0; i < configs.length; i++) {
            configs[i].priority = priorities[i];
            configs[i].meteredOverride = meteredness[i]
                    ? WifiConfiguration.METERED_OVERRIDE_METERED
                    : WifiConfiguration.METERED_OVERRIDE_NONE;
            PerAppInfo perAppInfo = new PerAppInfo(uids[i], packageNames[i], null);
            WifiNetworkSuggestion suggestion =
                    new WifiNetworkSuggestion(configs[i], null, appInteractions[i], false, true);
            suggestions[i] = new ExtendedWifiNetworkSuggestion(suggestion, perAppInfo);
        }
        return suggestions;
    }

    /**
     * Link scan results to the network suggestions.
     *
     * The shorter of the 2 input params will be used to loop over so the inputs don't
     * need to be of equal length.
     * If there are more scan details than suggestions, the remaining
     * scan details will be associated with a NULL suggestions.
     * If there are more suggestions than scan details, the remaining
     * suggestions will be associated with the last scan detail.
     */
    private void linkScanDetailsWithNetworkSuggestions(
            ScanDetail[] scanDetails, ExtendedWifiNetworkSuggestion[] suggestions) {
        if (suggestions == null || scanDetails == null) {
            return;
        }
        int minLength = Math.min(scanDetails.length, suggestions.length);

        // 1 to 1 mapping from scan detail to suggestion.
        for (int i = 0; i < minLength; i++) {
            ScanDetail scanDetail = scanDetails[i];
            final ExtendedWifiNetworkSuggestion matchingSuggestion = suggestions[i];
            HashSet<ExtendedWifiNetworkSuggestion> matchingSuggestions =
                    new HashSet<ExtendedWifiNetworkSuggestion>() {{
                        add(matchingSuggestion);
                    }};
            when(mWifiNetworkSuggestionsManager.getNetworkSuggestionsForScanDetail(eq(scanDetail)))
                    .thenReturn((matchingSuggestions));
        }
        if (scanDetails.length > suggestions.length) {
            // No match for the remaining scan details.
            for (int i = minLength; i < scanDetails.length; i++) {
                ScanDetail scanDetail = scanDetails[i];
                when(mWifiNetworkSuggestionsManager.getNetworkSuggestionsForScanDetail(
                        eq(scanDetail))).thenReturn(null);
            }
        } else if (suggestions.length > scanDetails.length) {
            // All the additional suggestions match the last scan detail.
            HashSet<ExtendedWifiNetworkSuggestion> matchingSuggestions = new HashSet<>();
            for (int i = minLength; i < suggestions.length; i++) {
                matchingSuggestions.add(suggestions[i]);
            }
            ScanDetail lastScanDetail = scanDetails[minLength - 1];
            when(mWifiNetworkSuggestionsManager.getNetworkSuggestionsForScanDetail(
                    eq(lastScanDetail))).thenReturn((matchingSuggestions));
        }
    }

    private void validateConnectableNetworks(List<Pair<ScanDetail, WifiConfiguration>> actual,
                                             String...expectedSsids) {
        Set<String> expectedSsidSet = new HashSet<>(Arrays.asList(expectedSsids));
        assertEquals(expectedSsidSet.size(), actual.size());

        for (Pair<ScanDetail, WifiConfiguration> candidate : actual) {
            // check if the scan detail matches the wificonfiguration.
            assertEquals("\"" + candidate.first.getSSID() + "\"", candidate.second.SSID);
            // check if both match one of the expected ssid's.
            assertTrue(expectedSsidSet.remove(candidate.first.getSSID()));
        }
        assertTrue(expectedSsidSet.isEmpty());
    }
}