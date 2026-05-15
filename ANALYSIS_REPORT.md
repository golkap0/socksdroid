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

## 3. Konfigurasi Instansi Native (libuz.so)
Aplikasi memungkinkan pengguna untuk menjalankan beberapa instansi `libuz.so` secara paralel (sebelumnya disebut sebagai `coreCount`, sekarang diperjelas menjadi `instanceCount`).
- **Analisis:** Menjalankan banyak instansi native secara bersamaan dapat meningkatkan beban kerja scheduler kernel. Meskipun `libuz.so` relatif ringan, setiap proses tetap memiliki overhead memori dan CPU sendiri.
- **Dampak:** Jika diatur terlalu tinggi, penggunaan CPU dapat meningkat karena manajemen banyak tunnel secara paralel, yang berkontribusi pada suhu perangkat. Namun, ini juga memberikan fleksibilitas untuk load balancing traffic.

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
