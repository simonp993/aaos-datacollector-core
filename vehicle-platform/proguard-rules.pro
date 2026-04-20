# Add project specific ProGuard rules here.

# Keep VhalPropertyService interface (used via DI injection)
-keep interface com.porsche.sportapps.vehicleplatform.VhalPropertyService { *; }

# Keep CarPropertyAdapter (instantiated via Hilt in real flavor)
-keep class com.porsche.sportapps.vehicleplatform.CarPropertyAdapter { *; }

# Keep FakeVhalPropertyService (instantiated via Hilt in mock flavor)
-keep class com.porsche.sportapps.vehicleplatform.fake.FakeVhalPropertyService { *; }

# Keep CarPropertyEventCallback implementations (registered via CarPropertyManager)
-keep class * implements android.car.hardware.property.CarPropertyManager$CarPropertyEventCallback { *; }

# Keep Porsche vendor VHAL property ID constants
-keep class vendor.porsche.hardware.vehiclevendorextension.VehicleProperty { *; }
