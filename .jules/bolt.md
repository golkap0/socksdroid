## 2025-05-15 - [Pola Memory Leak pada Fragment & Resource Cleanup]
Pembelajaran:
1. Fragment di Android memiliki siklus hidup yang lebih lama dari View-nya. Melewatkan nullifikasi referensi View di onDestroyView menyebabkan kebocoran memori (ViewHierarchy) saat Fragment ada di backstack.
2. Anonymous inner class yang memegang referensi implisit ke outer class (Activity/Fragment) sangat berbahaya jika diregistrasikan ke Service atau Handler yang berumur panjang. Penggunaan static inner class dengan WeakReference adalah solusi wajib.
3. Objek Process di Java (Runtime.exec) tidak otomatis menutup stream internalnya (stdin, stdout, stderr) setelah p.waitFor(). Ini bisa menyebabkan kebocoran File Descriptor yang signifikan di aplikasi yang sering menjalankan binary native.
4. Pola handoff pada socket forwarding membutuhkan pengelolaan status (handoffSuccessful) yang ketat untuk memastikan tidak ada socket yang "menggantung" jika delegasi ke thread pool gagal.

Tindakan:
- Selalu gunakan static inner class + WeakReference untuk ServiceConnection, Runnable, dan Listener di dalam Activity/Fragment.
- Implementasikan onDestroyView untuk membersihkan semua referensi View di Fragment.
- Pastikan semua Process streams dan Sockets ditutup di blok finally jika tidak berhasil di-handoff.

## 2025-05-15 - [Pola DNS Resolution Fail (ERR_NAME_NOT_RESOLVED) pada VPN]
Pembelajaran:
1. Penggunaan FixedThreadPool pada forwarder DNS/SOCKS dapat menyebabkan deadlock jika semua thread tertahan menunggu handshake SOCKS, sementara query DNS baru terus berdatangan. CachedThreadPool lebih sesuai untuk beban kerja I/O intensif yang tidak terduga seperti ini.
2. Kesalahan pada default port DNS (misal 9953 alih-alih 53) menyebabkan kegagalan resolusi saat konfigurasi profile tidak lengkap atau error.
3. Protocol SOCKS5 CONNECT harus secara eksplisit menangani tipe alamat (IPv4 vs IPv6) berdasarkan panjang byte dari InetAddress agar upstream proxy dapat memproses request dengan benar.

Tindakan:
- Selalu gunakan CachedThreadPool untuk DNS forwarder di dalam VpnService.
- Pastikan fallback DNS port adalah 53 (standar).
- Implementasikan pengecekan ip.length untuk menentukan ATYP (0x01 vs 0x04) pada request SOCKS5.

## 2025-05-15 - [Implementasi Navigation Drawer & Log Viewer]
Pembelajaran:
1. Penambahan Navigation Drawer di aplikasi berbasis Fragment lawas membutuhkan perubahan layout utama (activity_main.xml) untuk menyertakan DrawerLayout sebagai root.
2. Komunikasi data real-time dari Service ke Activity untuk fitur log viewer paling efisien dilakukan via AIDL method calls yang dipanggil secara berkala (polling) menggunakan Handler.
3. Mendukung AndroidX pada proyek lama membutuhkan konfigurasi android.useAndroidX=true di gradle.properties agar library seperti DrawerLayout dapat digunakan.

Tindakan:
- Selalu sediakan AIDL interface yang mendukung polling log untuk fitur diagnostik.
- Gunakan DrawerLayout untuk menampung fitur sekunder (log, settings tambahan) agar UI utama tetap bersih.
- Pastikan ikon hamburger (ic_menu) tersedia untuk memudahkan akses drawer bagi user.
