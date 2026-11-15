import importlib.util
from pathlib import Path
import unittest

spec = importlib.util.spec_from_file_location('private_config', Path(__file__).with_name('prepare-private-tv-config.py'))
config = importlib.util.module_from_spec(spec)
spec.loader.exec_module(config)

class PrivateConfigTest(unittest.TestCase):
    def test_aliases_and_properties_escaping(self):
        keys, sources = config.normalize('# comment\nTMDB API KEY=sample\nReal-Debrid: rd-sample\n')
        self.assertEqual({'tmdb': 'sample', 'realdebrid': 'rd-sample'}, keys)
        self.assertEqual([], sources)
        self.assertEqual(r'a\=b\:c\ d\\e', config.escape('a=b:c d\\e'))

    def test_duplicate_xtream_forms_produce_one_source(self):
        _, sources = config.normalize('Server: https://example.test\nUsername: owner\nPassword: sample\n'
                                      'EPG URL: https://example.test/guide.xml\n'
                                      'https://example.test/get.php?username=owner&password=sample&type=m3u_plus')
        self.assertEqual(1, len(sources))
        self.assertEqual('https://example.test/guide.xml', sources[0]['epgUrl'])

    def test_conflicting_sources_remain_distinct_for_explicit_selection(self):
        _, sources = config.normalize('https://example.test/get.php?username=one&password=sample\n'
                                      'https://example.test/get.php?username=two&password=sample')
        self.assertEqual(2, len(sources))

    def test_incomplete_source_is_not_importable(self):
        _, sources = config.normalize('Server: https://example.test\nUsername: owner')
        self.assertEqual([], sources)

if __name__ == '__main__':
    unittest.main()
