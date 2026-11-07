import unittest
from omni_branding_guard import BRANDING_URLS, same_native_app_config

class BrandingGuardTest(unittest.TestCase):
    def test_exact_three_public_urls_are_allowed(self):
        old='\n'.join(BRANDING_URLS.values())+'\nSOCKS_PORT = 10808'
        new='\n'.join(BRANDING_URLS.keys())+'\nSOCKS_PORT = 10808'
        self.assertTrue(same_native_app_config(old,new))
        self.assertFalse(same_native_app_config(old,new.replace('10808','10809')))
        self.assertFalse(same_native_app_config(old,new.replace('imgutss/Omni','attacker/Other')))
        self.assertFalse(same_native_app_config(old,new+'\nextra runtime setting'))
    def test_unchanged_configuration_is_allowed(self):
        self.assertTrue(same_native_app_config('unchanged','unchanged'))
if __name__=='__main__':unittest.main()
