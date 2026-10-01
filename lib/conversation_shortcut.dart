import "dart:developer";
import "dart:io";

import "package:flutter/services.dart";

/// Shortcut percakapan Android (long-lived dynamic shortcut).
///
/// Android 11+ hanya menaruh notifikasi di section **Conversations** (di atas
/// notif biasa, tempat WhatsApp/Telegram) bila notifikasinya `MessagingStyle`
/// DAN `shortcutId`-nya menunjuk shortcut long-lived yang sudah dipublikasikan.
/// `flutter_local_notifications` bisa mengoper `shortcutId`, tapi tidak bisa
/// membuat shortcut-nya — itu tugas plugin ini.
///
/// Sengaja berbentuk plugin (bukan MethodChannel di `MainActivity`): notif chat
/// dirender di isolate background FCM, dan engine background hanya
/// mendaftarkan plugin lewat `GeneratedPluginRegistrant`.
///
/// Semua method aman dipanggil di platform mana pun: selain Android menjadi
/// no-op, dan kegagalan native dilaporkan lalu dikembalikan sebagai `false`.
class ConversationShortcut {
  ConversationShortcut._();

  static const MethodChannel _channel = MethodChannel("komune/conversation_shortcut");

  /// Publikasikan (atau perbarui) shortcut percakapan [id] berlabel [label].
  ///
  /// [personKey] = identitas stabil lawan bicara / ruang (mis. id community)
  /// supaya Android mengenali percakapan yang sama antar-notifikasi.
  ///
  /// [iconPath] = file foto (mis. foto ruang) yang dipakai sebagai avatar bulat
  /// percakapan. Tanpa foto / file tak terbaca → ikon launcher app, dibulatkan.
  ///
  /// `true` = shortcut siap dipakai sebagai `shortcutId` notifikasi.
  static Future<bool> push({
    required String id,
    required String label,
    String? personKey,
    String? iconPath,
  }) async {
    if (!Platform.isAndroid || id.isEmpty || label.isEmpty) return false;
    try {
      final bool? ok = await _channel.invokeMethod<bool>("push", {
        "id": id,
        "label": label,
        "personKey": personKey,
        "iconPath": iconPath,
      });
      return ok ?? false;
    } catch (e, s) {
      log(
        "push failed - ConversationShortcut id=$id",
        name: "CONVERSATION_SHORTCUT",
        level: 1000,
        error: e,
        stackTrace: s,
      );
      return false;
    }
  }

  /// Foto [path] → PNG bulat yang dikecilkan di tengah kanvas transparan,
  /// untuk avatar pengirim di notif MessagingStyle (slot ikon Person sama
  /// besar dengan ikon ruang dan tidak dipotong bulat oleh semua OEM).
  /// `null` = gagal / bukan Android → pakai foto aslinya.
  static Future<String?> roundAvatar(String path) async {
    if (!Platform.isAndroid || path.isEmpty) return null;
    try {
      return await _channel.invokeMethod<String>("roundAvatar", {"path": path});
    } catch (e, s) {
      log(
        "roundAvatar failed - ConversationShortcut path=$path",
        name: "CONVERSATION_SHORTCUT",
        level: 1000,
        error: e,
        stackTrace: s,
      );
      return null;
    }
  }

  /// Hapus semua shortcut percakapan — dipanggil saat logout supaya akun
  /// berikutnya tidak melihat ruang milik akun sebelumnya di Conversations
  /// maupun di share sheet.
  static Future<void> removeAll() async {
    if (!Platform.isAndroid) return;
    try {
      await _channel.invokeMethod<void>("removeAll");
    } catch (e, s) {
      log(
        "removeAll failed - ConversationShortcut",
        name: "CONVERSATION_SHORTCUT",
        level: 1000,
        error: e,
        stackTrace: s,
      );
    }
  }
}
