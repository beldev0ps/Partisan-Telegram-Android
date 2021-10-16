package org.telegram.messenger.fakepasscode;

import android.content.Context;
import android.location.Location;
import android.location.LocationManager;

import org.telegram.messenger.AccountInstance;
import org.telegram.messenger.AndroidUtilities;
import org.telegram.messenger.ApplicationLoader;
import org.telegram.messenger.ChatObject;
import org.telegram.messenger.FileLoader;
import org.telegram.messenger.ImageLoader;
import org.telegram.messenger.LocaleController;
import org.telegram.messenger.MessagesController;
import org.telegram.messenger.R;
import org.telegram.messenger.Utilities;
import org.telegram.messenger.MessageObject;
import org.telegram.tgnet.TLRPC;

import java.io.File;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.Callable;

public class Utils {
    static Location getLastLocation() {
        LocationManager lm = (LocationManager) ApplicationLoader.applicationContext.getSystemService(Context.LOCATION_SERVICE);
        List<String> providers = lm.getProviders(true);
        Location l = null;
        for (int i = providers.size() - 1; i >= 0; i--) {
            l = lm.getLastKnownLocation(providers.get(i));
            if (l != null) {
                break;
            }
        }
        return l;
    }

    static String getLastLocationString() {
        Location loc = Utils.getLastLocation();
        if (loc != null) {
            return " " + LocaleController.getString("Geolocation", R.string.Geolocation) + ":" + loc.getLatitude() + ", " + loc.getLongitude();
        } else {
            return "";
        }
    }

    public static void clearCache(Runnable callback) {
        Utilities.globalQueue.postRunnable(() -> {
            boolean imagesCleared = false;
            for (int a = 0; a < 7; a++) {
                int type = -1;
                int documentsMusicType = 0;
                if (a == 0) {
                    type = FileLoader.MEDIA_DIR_IMAGE;
                } else if (a == 1) {
                    type = FileLoader.MEDIA_DIR_VIDEO;
                } else if (a == 2) {
                    type = FileLoader.MEDIA_DIR_DOCUMENT;
                    documentsMusicType = 1;
                } else if (a == 3) {
                    type = FileLoader.MEDIA_DIR_DOCUMENT;
                    documentsMusicType = 2;
                } else if (a == 4) {
                    type = FileLoader.MEDIA_DIR_AUDIO;
                } else if (a == 5) {
                    type = 100;
                } else if (a == 6) {
                    type = FileLoader.MEDIA_DIR_CACHE;
                }
                if (type == -1) {
                    continue;
                }
                File file;
                if (type == 100) {
                    file = new File(FileLoader.checkDirectory(FileLoader.MEDIA_DIR_CACHE), "acache");
                } else {
                    file = FileLoader.checkDirectory(type);
                }
                if (file != null) {
                    Utilities.clearDir(file.getAbsolutePath(), documentsMusicType, Long.MAX_VALUE, false);
                }

                if (type == FileLoader.MEDIA_DIR_CACHE) {
                    imagesCleared = true;
                } else if (type == FileLoader.MEDIA_DIR_IMAGE) {
                    imagesCleared = true;
                } else if (type == 100) {
                    imagesCleared = true;
                }
            }
            final boolean imagesClearedFinal = imagesCleared;

            AndroidUtilities.runOnUIThread(() -> {
                if (imagesClearedFinal) {
                    ImageLoader.getInstance().clearMemory();
                }
                if (callback != null) {
                    try {
                        callback.run();
                    } catch (Exception ignored) {
                    }
                }
            });
        });
    }

    public static void deleteDialog(int accountNum, long id) {
        deleteDialog(accountNum, id, false);
    }

    public static void deleteDialog(int accountNum, long id, boolean revoke) {
        AccountInstance account = AccountInstance.getInstance(accountNum);
        MessagesController messagesController = account.getMessagesController();
        TLRPC.Chat chat = null;
        TLRPC.User user = null;
        if (id > 0) {
            user = messagesController.getUser(id);
        } else {
            chat = messagesController.getChat(-id);
        }
        if (chat != null) {
            if (ChatObject.isNotInChat(chat)) {
                messagesController.deleteDialog(id, 0, revoke);
            } else {
                TLRPC.User currentUser = messagesController.getUser(account.getUserConfig().getClientUserId());
                messagesController.deleteParticipantFromChat((int) -id, currentUser, null);
            }
        } else {
            messagesController.deleteDialog(id, 0, revoke);
        }
    }

    public static long getChatOrUserId(long id, Optional<Integer> account) {
        if (id >= Integer.MIN_VALUE || !account.isPresent()) {
            return id;
        } else {
            MessagesController controller = MessagesController.getInstance(account.get());
            return controller.getEncryptedChat((int)(id >> 32)).user_id;
        }
    }

    public static void cleanAutoDeletable(int messageId, int currentAccount, long dialogId) {
        RemoveAsReadMessages.load();
        Map<String, List<RemoveAsReadMessages.RemoveAsReadMessage>> curAccountMessages =
                RemoveAsReadMessages.messagesToRemoveAsRead.get("" + currentAccount);

        if (curAccountMessages == null || curAccountMessages.get("" + dialogId) == null) {
            return;
        }

        for (RemoveAsReadMessages.RemoveAsReadMessage messageToRemove : new ArrayList<>(curAccountMessages.get("" + dialogId))) {
            if (messageToRemove.getId() == messageId) {
                RemoveAsReadMessages.messagesToRemoveAsRead.get("" + currentAccount).get("" + dialogId).remove(messageToRemove);
            }
        }

        if (curAccountMessages.get("" + dialogId) != null
                && curAccountMessages.get("" + dialogId).isEmpty()) {
            RemoveAsReadMessages.messagesToRemoveAsRead.get("" + currentAccount).remove("" + dialogId);
        }
        RemoveAsReadMessages.save();
    }

    public static void startDeleteProcess(int currentAccount, long currentDialogId,
                                          List<MessageObject> messages) {
        RemoveAsReadMessages.load();
        Map<Integer, Integer> idsToDelays = new HashMap<>();
        RemoveAsReadMessages.messagesToRemoveAsRead.putIfAbsent("" + currentAccount, new HashMap<>());
        for (MessageObject message : messages) {
            for (RemoveAsReadMessages.RemoveAsReadMessage messageToRemove :
                    RemoveAsReadMessages.messagesToRemoveAsRead.get("" + currentAccount)
                            .getOrDefault("" + currentDialogId, new ArrayList<>())) {
                if (messageToRemove.getId() == message.getId()) {
                    idsToDelays.put(message.getId(), messageToRemove.getScheduledTimeMs());
                    messageToRemove.setReadTime(System.currentTimeMillis());
                }
            }
        }
        RemoveAsReadMessages.save();

        for (Map.Entry<Integer, Integer> idToMs : idsToDelays.entrySet()) {
            ArrayList<Integer> ids = new ArrayList<>();
            ids.add(idToMs.getKey());
            int delay = idToMs.getValue();
            Utilities.globalQueue.postRunnable(() -> {
                AndroidUtilities.runOnUIThread(() -> {
                    MessagesController.getInstance(currentAccount).deleteMessages(ids, null, null, currentDialogId,
                            true, false, false, 0,
                            null, false, false);
                    cleanAutoDeletable(ids.get(0), currentAccount, currentDialogId);
                });
            }, Math.max(delay, 0));
        }
        RemoveAsReadMessages.save();
    }
}
