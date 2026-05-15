# Analisis Mendalam: Konsumsi Baterai Tinggi dan Panas pada SocksDroid

Berdasarkan analisis terhadap kode sumber, berikut adalah faktor-faktor utama yang menyebabkan aplikasi VPN ini boros baterai dan membuat perangkat menjadi panas:

## 1. Mekanisme Polling UI yang Tidak Efisien (ProfileFragment.java)
Aplikasi menggunakan `StateRunnable` di `ProfileFragment.java` yang berjalan setiap **1 detik** untuk memperbarui status VPN.
- **Masalah:** Polling ini memanggil `mBinder.isRunning()` melalui IPC (Inter-Process Communication) terus-menerus meskipun aplikasi berada di latar depan (foreground) dan tidak ada perubahan status.
- **Dampak:** CPU tidak bisa masuk ke mode hemat daya karena selalu ada aktivitas setiap detik. IPC juga memiliki overhead yang cukup besar dalam hal konsumsi daya.

## 2. Arsitektur Multi-Threading pada SocksForwarder (SocksVpnService.java)
`SocksForwarder` menggunakan model **Blocking I/O (BIO)** dengan thread per koneksi.
- **Masalah:** Untuk setiap koneksi baru, aplikasi membuat dua thread baru (`pipe(fClient, fProxy)` dan `pipe(fProxy, fClient)`).
- **Dampak:** Jika ada banyak koneksi (misal membuka web dengan banyak aset), jumlah thread akan melonjak drastis. Hal ini menyebabkan penggunaan memori tinggi dan beban CPU akibat *context switching* antar thread yang sangat sering.

## 3. Proliferasi Proses Native (libuz.so)
Di `SocksVpnService.start()`, aplikasi mencoba menjalankan beberapa instansi `libuz.so` berdasarkan jumlah core CPU (`workerCoreCount`).
- **Masalah:** Menjalankan banyak proses native secara bersamaan meningkatkan beban kerja scheduler kernel secara signifikan. Setiap proses memiliki overhead memori dan CPU sendiri.
- **Dampak:** Penggunaan CPU meningkat tajam karena sinkronisasi antar proses dan manajemen banyak tunnel secara paralel, yang berujung pada panas berlebih.

## 4. Manajemen Proses Native yang Kurang Optimal (Utility.java)
Metode `Utility.exec()` menjalankan proses native tetapi tidak menangani aliran output (`stdout`/`stderr`) secara aktif.
- **Masalah:** Meskipun output diabaikan, proses native mungkin terhambat jika buffer output penuh (deadlock), atau sistem harus bekerja lebih keras untuk membuang output tersebut.
- **Dampak:** Efisiensi eksekusi proses native berkurang.

## 5. Overload DNS Forwarding
Aplikasi menggunakan rantai forwarding DNS yang cukup panjang:
`tun2socks` -> `pdnsd` -> `SocksForwarder` -> `libuz.so` -> Server.
- **Masalah:** Setiap lapisan menambah latensi dan pemrosesan CPU tambahan untuk setiap paket DNS.
- **Dampak:** Peningkatan latensi dan beban CPU kecil namun kumulatif setiap kali ada aktivitas jaringan.

---

# Rekomendasi Optimalisasi (Untuk Tahap Selanjutnya)

1. **Ubah Polling menjadi Event-Driven:** Gunakan callback dari `VpnService` ke UI hanya saat status berubah, bukan polling setiap detik.
2. **Gunakan Java NIO:** Ganti `SocksForwarder` yang berbasis BIO menjadi NIO (Non-blocking I/O) dengan `Selector` untuk menangani banyak koneksi hanya dengan satu atau sedikit thread.
3. **Batasi Instansi Native:** Cukup jalankan satu atau dua instansi `libuz.so` yang efisien daripada mencoba menyamai jumlah core CPU.
4. **Optimasi Alur DNS:** Jika memungkinkan, sederhanakan alur forwarding DNS untuk mengurangi overhead.
5. **Implementasi No-Log Policy secara Internal:** Pastikan tidak ada logging yang berjalan di mode produksi untuk mengurangi beban I/O.
