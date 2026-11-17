# Keep Android components and location callbacks intact if minification is ever enabled
-keep class app.locationreply.mobile.MainActivity { *; }
-keep class app.locationreply.mobile.SmsRequestReceiver { *; }
-keep class app.locationreply.mobile.LocationService { *; }
-keep class app.locationreply.mobile.BootReceiver { *; }
-keep class app.locationreply.mobile.SmsDispatcher { *; }
-keep class app.locationreply.mobile.PrefsHelper { *; }
