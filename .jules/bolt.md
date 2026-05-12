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
