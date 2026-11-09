import unittest
import io
import struct
import zipfile
from package_apk import verify_update_permissions, python_native_libraries


class UpdatePermissionTest(unittest.TestCase):
    def test_rc4_update_job_permissions_are_rejected(self):
        # rc4 declares Internet/boot permissions, but neither grants network-state access.
        badging = "\n".join([
            "uses-permission: name='android.permission.INTERNET'",
            "uses-permission: name='android.permission.RECEIVE_BOOT_COMPLETED'",
            "uses-permission: name='android.permission.POST_NOTIFICATIONS'",
        ])
        with self.assertRaisesRegex(ValueError, 'ACCESS_NETWORK_STATE'):
            verify_update_permissions(badging)

    def test_network_state_permission_allows_update_jobs(self):
        verify_update_permissions("uses-permission: name='android.permission.ACCESS_NETWORK_STATE'\n")

    def test_similarly_named_permission_does_not_grant_access(self):
        with self.assertRaisesRegex(ValueError, 'ACCESS_NETWORK_STATE'):
            verify_update_permissions("uses-permission: name='android.permission.ACCESS_NETWORK_STATE_EXTRA'\n")


class EmbeddedNativeLibraryTest(unittest.TestCase):
    def archive(self, alignment):
        elf = bytearray(120)
        elf[:6] = b'\x7fELF\x02\x01'
        struct.pack_into('<Q', elf, 32, 64)
        struct.pack_into('<HH', elf, 54, 56, 1)
        struct.pack_into('<IIQQQQQQ', elf, 64, 1, 5, 0, 0, 0, 120, 120, alignment)
        nested = io.BytesIO()
        with zipfile.ZipFile(nested, 'w') as z:
            z.writestr('numpy.libs/libopenblas.so.0', elf)
        apk = io.BytesIO()
        with zipfile.ZipFile(apk, 'w') as z:
            z.writestr('assets/chaquopy/requirements-arm64-v8a.imy', nested.getvalue())
        return zipfile.ZipFile(apk)

    def test_rejects_4kb_library_hidden_inside_python_assets(self):
        with self.archive(4096) as apk:
            with self.assertRaisesRegex(ValueError, '16 KB'):
                python_native_libraries(apk)

    def test_accepts_and_reports_16kb_embedded_library(self):
        with self.archive(16384) as apk:
            libraries = python_native_libraries(apk)
            self.assertEqual(libraries, {
                'assets/chaquopy/requirements-arm64-v8a.imy!/numpy.libs/libopenblas.so.0': [16384]
            })
