import os
from pathlib import Path
import re
import subprocess
import tempfile
import unittest


class NativeBootstrapTest(unittest.TestCase):
    def git(self, *args):
        return subprocess.check_output(['git', *map(str, args)], stderr=subprocess.DEVNULL, text=True).strip()

    def commit(self, repo):
        self.git('-C', repo, 'add', '.')
        self.git('-C', repo, '-c', 'user.name=Fixture', '-c', 'user.email=fixture@example.test', 'commit', '-m', 'fixture')
        return self.git('-C', repo, 'rev-parse', 'HEAD')

    def setUp(self):
        self.temporary = tempfile.TemporaryDirectory(prefix='runlocal-native-bootstrap-')
        self.addCleanup(self.temporary.cleanup)
        root = Path(self.temporary.name)
        ggml = root / 'ggml-source'
        ggml.mkdir()
        self.git('init', ggml)
        (ggml / 'CMakeLists.txt').write_text('# dependency fixture\n')
        self.commit(ggml)
        source = root / 'native-source'
        source.mkdir()
        self.git('init', source)
        (source / 'CMakeLists.txt').write_text('# runtime fixture\n')
        self.git('-C', source, '-c', 'protocol.file.allow=always', 'submodule', 'add', str(ggml), 'ggml')
        self.revision = self.commit(source)
        self.project = root / 'project'
        (self.project / 'scripts').mkdir(parents=True)
        self.script = self.project / 'scripts' / 'bootstrap-native.sh'
        script = Path(__file__).with_name('bootstrap-native.sh').read_text()
        script = script.replace('https://github.com/leejet/stable-diffusion.cpp.git', str(source))
        script = re.sub(r'^revision=[0-9a-f]+$', f'revision={self.revision}', script, flags=re.M)
        self.script.write_text(script)
        self.target = self.project / 'third_party' / 'stable-diffusion.cpp'

    def bootstrap(self):
        return subprocess.run(['bash', str(self.script)], env={**os.environ, 'GIT_ALLOW_PROTOCOL': 'file'}, capture_output=True, text=True)

    def test_fresh_checkout_initializes_pinned_runtime_and_submodule(self):
        first = self.bootstrap()
        self.assertEqual(0, first.returncode, first.stderr)
        self.assertEqual(self.revision, self.git('-C', self.target, 'rev-parse', 'HEAD'))
        self.assertTrue((self.target / 'ggml' / 'CMakeLists.txt').is_file())
        self.assertEqual('', self.git('-C', self.target, 'status', '--porcelain'))
        second = self.bootstrap()
        self.assertEqual(0, second.returncode, second.stderr)

    def test_existing_user_changes_are_preserved_and_block_checkout(self):
        first = self.bootstrap()
        self.assertEqual(0, first.returncode, first.stderr)
        changed = self.target / 'CMakeLists.txt'
        changed.write_text('keep my changes\n')
        result = self.bootstrap()
        self.assertNotEqual(0, result.returncode)
        self.assertIn('Native dependency has changes', result.stderr)
        self.assertEqual('keep my changes\n', changed.read_text())
