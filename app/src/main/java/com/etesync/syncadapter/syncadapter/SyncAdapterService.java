/*
 * Copyright © 2013 – 2016 Ricki Hirner (bitfire web engineering).
 * All rights reserved. This program and the accompanying materials
 * are made available under the terms of the GNU Public License v3.0
 * which accompanies this distribution, and is available at
 * http://www.gnu.org/licenses/gpl.html
 */

package com.etesync.syncadapter.syncadapter;

import android.accounts.Account;
import android.app.Notification;
import android.app.PendingIntent;
import android.app.Service;
import android.content.AbstractThreadedSyncAdapter;
import android.content.ContentProviderClient;
import android.content.Context;
import android.content.Intent;
import android.content.SyncResult;
import android.net.ConnectivityManager;
import android.net.NetworkInfo;
import android.net.wifi.WifiInfo;
import android.net.wifi.WifiManager;
import android.os.Bundle;
import android.os.IBinder;
import android.support.annotation.NonNull;
import android.support.annotation.Nullable;
import android.support.v4.app.NotificationCompat;
import android.support.v4.app.NotificationManagerCompat;
import android.support.v4.util.Pair;

import java.util.HashMap;
import java.util.LinkedList;
import java.util.List;
import java.util.Map;
import java.util.logging.Level;

import com.etesync.syncadapter.AccountSettings;
import com.etesync.syncadapter.App;
import com.etesync.syncadapter.Constants;
import com.etesync.syncadapter.HttpClient;
import com.etesync.syncadapter.InvalidAccountException;
import com.etesync.syncadapter.R;
import com.etesync.syncadapter.journalmanager.Crypto;
import com.etesync.syncadapter.journalmanager.Exceptions;
import com.etesync.syncadapter.journalmanager.JournalManager;
import com.etesync.syncadapter.model.CollectionInfo;
import com.etesync.syncadapter.model.JournalEntity;
import com.etesync.syncadapter.model.JournalModel;
import com.etesync.syncadapter.model.ServiceEntity;
import com.etesync.syncadapter.ui.PermissionsActivity;

import io.requery.Persistable;
import io.requery.sql.EntityDataStore;
import okhttp3.HttpUrl;
import okhttp3.OkHttpClient;

//import com.android.vending.billing.IInAppBillingService;

public abstract class SyncAdapterService extends Service {

    abstract protected AbstractThreadedSyncAdapter syncAdapter();

    @Nullable
    @Override
    public IBinder onBind(Intent intent) {
        return syncAdapter().getSyncAdapterBinder();
    }


    public static abstract class SyncAdapter extends AbstractThreadedSyncAdapter {

        public SyncAdapter(Context context) {
            super(context, false);
        }

        @Override
        public void onPerformSync(Account account, Bundle extras, String authority, ContentProviderClient provider, SyncResult syncResult) {
            App.log.log(Level.INFO, authority + " sync of " + account + " has been initiated.", extras.keySet().toArray());

            // required for dav4android (ServiceLoader)
            Thread.currentThread().setContextClassLoader(getContext().getClassLoader());
        }

        @Override
        public void onSecurityException(Account account, Bundle extras, String authority, SyncResult syncResult) {
            App.log.log(Level.WARNING, "Security exception when opening content provider for " + authority);
            syncResult.databaseError = true;

            Intent intent = new Intent(getContext(), PermissionsActivity.class);
            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);

            Notification notify = new NotificationCompat.Builder(getContext())
                    .setSmallIcon(R.drawable.ic_error_light)
                    .setLargeIcon(App.getLauncherBitmap(getContext()))
                    .setContentTitle(getContext().getString(R.string.sync_error_permissions))
                    .setContentText(getContext().getString(R.string.sync_error_permissions_text))
                    .setContentIntent(PendingIntent.getActivity(getContext(), 0, intent, PendingIntent.FLAG_UPDATE_CURRENT))
                    .setCategory(NotificationCompat.CATEGORY_ERROR)
                    .build();
            NotificationManagerCompat nm = NotificationManagerCompat.from(getContext());
            nm.notify(Constants.NOTIFICATION_PERMISSIONS, notify);
        }

        protected boolean checkSyncConditions(@NonNull AccountSettings settings) {
            if (settings.getSyncWifiOnly()) {
                ConnectivityManager cm = (ConnectivityManager) getContext().getSystemService(CONNECTIVITY_SERVICE);
                NetworkInfo network = cm.getActiveNetworkInfo();
                if (network == null) {
                    App.log.info("No network available, stopping");
                    return false;
                }
                if (network.getType() != ConnectivityManager.TYPE_WIFI || !network.isConnected()) {
                    App.log.info("Not on connected WiFi, stopping");
                    return false;
                }

                String onlySSID = settings.getSyncWifiOnlySSID();
                if (onlySSID != null) {
                    onlySSID = "\"" + onlySSID + "\"";
                    WifiManager wifi = (WifiManager) getContext().getApplicationContext().getSystemService(WIFI_SERVICE);
                    WifiInfo info = wifi.getConnectionInfo();
                    if (info == null || !onlySSID.equals(info.getSSID())) {
                        App.log.info("Connected to wrong WiFi network (" + info.getSSID() + ", required: " + onlySSID + "), ignoring");
                        return false;
                    }
                }
            }
            return true;
        }

        protected class RefreshCollections {
            final private Account account;
            final private Context context;
            final private CollectionInfo.Type serviceType;

            RefreshCollections(Account account, CollectionInfo.Type serviceType) {
                this.account = account;
                this.serviceType = serviceType;
                context = getContext();
            }

            void run() throws Exceptions.HttpException, Exceptions.IntegrityException, InvalidAccountException, Exceptions.GenericCryptoException {
                App.log.info("Refreshing " + serviceType + " collections of service #" + serviceType.toString());

                AccountSettings settings = new AccountSettings(context, account);
                OkHttpClient httpClient = HttpClient.create(context, settings);

                JournalManager journalsManager = new JournalManager(httpClient, HttpUrl.get(settings.getUri()));

                List<Pair<JournalManager.Journal, CollectionInfo>> journals = new LinkedList<>();

                for (JournalManager.Journal journal : journalsManager.list()) {
                    Crypto.CryptoManager crypto;
                    if (journal.getKey() != null) {
                        crypto = new Crypto.CryptoManager(journal.getVersion(), settings.getKeyPair(), journal.getKey());
                    } else {
                        crypto = new Crypto.CryptoManager(journal.getVersion(), settings.password(), journal.getUid());
                    }

                    journal.verify(crypto);

                    CollectionInfo info = CollectionInfo.fromJson(journal.getContent(crypto));
                    info.updateFromJournal(journal);

                    if (info.type.equals(serviceType)) {
                        journals.add(new Pair<>(journal, info));
                    }
                }

                if (journals.isEmpty()) {
                    CollectionInfo info = CollectionInfo.defaultForServiceType(serviceType);
                    info.uid = JournalManager.Journal.genUid();
                    Crypto.CryptoManager crypto = new Crypto.CryptoManager(info.version, settings.password(), info.uid);
                    JournalManager.Journal journal = new JournalManager.Journal(crypto, info.toJson(), info.uid);
                    journalsManager.create(journal);
                    journals.add(new Pair<>(journal, info));
                }

                saveCollections(journals);
            }

            private void saveCollections(Iterable<Pair<JournalManager.Journal, CollectionInfo>> journals) {
                EntityDataStore<Persistable> data = ((App) context.getApplicationContext()).getData();
                ServiceEntity service =  JournalModel.Service.fetch(data, account.name, serviceType);

                Map<String, JournalEntity> existing = new HashMap<>();
                for (JournalEntity journalEntity : JournalEntity.getJournals(data, service)) {
                    existing.put(journalEntity.getUid(), journalEntity);
                }

                for (Pair<JournalManager.Journal, CollectionInfo> pair : journals) {
                    JournalManager.Journal journal = pair.first;
                    CollectionInfo collection = pair.second;
                    App.log.log(Level.FINE, "Saving collection", journal.getUid());

                    collection.serviceID = service.getId();
                    JournalEntity journalEntity = JournalEntity.fetchOrCreate(data, collection);
                    journalEntity.setOwner(journal.getOwner());
                    journalEntity.setEncryptedKey(journal.getKey());
                    journalEntity.setReadOnly(journal.isReadOnly());
                    journalEntity.setDeleted(false);
                    data.upsert(journalEntity);

                    existing.remove(collection.uid);
                }

                for (JournalEntity journalEntity : existing.values()) {
                    App.log.log(Level.FINE, "Deleting collection", journalEntity.getUid());

                    journalEntity.setDeleted(true);
                    data.update(journalEntity);
                }
            }
        }
    }
}
