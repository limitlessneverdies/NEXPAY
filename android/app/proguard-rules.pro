# Obfuscation is a cost increase, not a promise against reverse engineering.
# No issuer secrets or shared API credentials are included in the APK.
-keepattributes Signature,InnerClasses,EnclosingMethod
-repackageclasses ''
-allowaccessmodification
