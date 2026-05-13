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

## 2025-05-15 - [Perbaikan UI Drawer & Kontras Teks]
Pembelajaran:
1. Penggunaan warna hardcoded (seperti #FFFFFF) pada layout dapat merusak konsistensi UI jika aplikasi menggunakan tema sistem (Material/Dark). Selalu gunakan atribut tema seperti ?android:attr/windowBackground.
2. Teks yang tidak muncul pada drawer seringkali disebabkan oleh warna teks default yang sama dengan background (misal putih di atas putih). Menggunakan ?android:attr/textColorPrimary menjamin teks tetap terbaca sesuai tema yang aktif.

Tindakan:
- Gunakan atribut tema untuk background dan warna teks pada komponen UI baru.
- Pastikan DrawerLayout menggunakan background yang konsisten dengan konten utama.

## 2025-05-15 - [Pola Capture Log Native Binary]
Pembelajaran:
1. Log dari binary native yang dijalankan via Runtime.exec tidak otomatis masuk ke logcat atau UI Android. Log tersebut dialirkan ke InputStream (stdout) dan ErrorStream (stderr) milik objek Process.
2. Untuk menampilkan log native secara real-time di UI, kita harus membuat thread pembaca khusus untuk setiap stream tersebut dan meneruskannya ke listener yang terhubung ke UI.
3. Mengabaikan penutupan stream pada Process pembaca log dapat menyebabkan kebocoran file descriptor yang parah.

Tindakan:
- Implementasikan OnLogListener pada Utility.exec untuk menangkap output stdout/stderr.
- Gunakan thread terpisah untuk membaca stream agar tidak memblokir eksekusi utama binary.

## 2025-05-15 - [Pola Fix CI Compilation Error]
Pembelajaran:
1. Saat memindahkan method ke scope yang lebih luas (dari inner class ke outer class), pastikan semua referensi lama di-update. Kesalahan 'cannot find symbol' sering terjadi karena method dipanggil di scope yang salah atau belum didefinisikan saat class dikompilasi.
2. Lambda expression di Java menangkap scope sekitarnya; jika method dipanggil di dalam lambda yang dieksekusi di thread terpisah, method tersebut harus thread-safe (misal menggunakan synchronized).

Tindakan:
- Pastikan visibilitas method (private/protected/public) sesuai dengan scope pemanggilnya.
- Verifikasi kompilasi lokal atau cek ulang struktur class sebelum melakukan push jika melakukan refactoring besar.
