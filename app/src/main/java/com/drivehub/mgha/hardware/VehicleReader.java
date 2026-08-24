package com.drivehub.mgha.hardware;

import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.ServiceConnection;
import android.location.Location;
import android.location.LocationManager;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.os.Parcel;
import android.util.Log;

import com.drivehub.mgha.net.WifiHelper;
import com.drivehub.mgha.prefs.HaSettings;

import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.util.concurrent.ConcurrentHashMap;

/**
 * MG4 EH32 CarPropertyManager (yansıma) okuyucu — DriveHub Dort ile aynı property ID'leri.
 */
public final class VehicleReader {
    private static final String TAG = "MGHA_HW";

    private static final int AREA_GLOBAL = 0x01000000;

    private static final int PROP_SPEED = 0x11600207;
    private static final int PROP_SOC = 0x2160F404;
    private static final int PROP_RANGE = 0x2140F41C;
    private static final int PROP_BATT_VOLT = 0x2160F406;
    private static final int PROP_CHR_AMP_ACT = 0x2160F407;
    private static final int PROP_AC_AMP = 0x2160F43C;
    private static final int PROP_AC_VOLT = 0x2160F43D;
    private static final int PROP_CHG_STATUS = 0x2140F409;
    private static final int PROP_TOTAL_MILEAGE = 0x21401566;
    private static final int PROP_TIRE_PRESSURE_FL = 0x21401553;
    private static final int PROP_TIRE_PRESSURE_FR = 0x21401554;
    private static final int PROP_TIRE_PRESSURE_RL = 0x21401555;
    private static final int PROP_TIRE_PRESSURE_RR = 0x21401556;

    private static final String SAIC_MAP_PACKAGE = "com.saicmotor.adapterservice";
    private static final String SAIC_MAP_SERVICE_CLASS = SAIC_MAP_PACKAGE + ".services.MapService";
    private static final String SAIC_MAP_DESCRIPTOR = "com.saicmotor.adapterservice.IMapService";
    private static final int TX_SAIC_MAP_GET_SENSOR_TEMPERATURE = 0x43;

    private static final ConcurrentHashMap<Integer, Object> sBmsCache = new ConcurrentHashMap<>();

    private static Context sAppContext;
    private static Object sCar;
    private static Object sCarPropertyManager;
    private static boolean sCarBindAttempted;
    private static boolean sMapBindAttempted;
    private static IBinder sSaicMapBinder;

    private static final ServiceConnection sMapConnection = new ServiceConnection() {
        @Override
        public void onServiceConnected(ComponentName name, IBinder service) {
            sSaicMapBinder = service;
            Log.i(TAG, "SAIC MapService bağlı");
        }

        @Override
        public void onServiceDisconnected(ComponentName name) {
            sSaicMapBinder = null;
        }
    };

    private VehicleReader() {}

    public static synchronized void init(Context context) {
        if (context == null) return;
        sAppContext = context.getApplicationContext();
        // Telefonda / sim’de android.car yok; bağlanmaya çalışma
        if (WifiHelper.isSim()) {
            Log.i(TAG, "sim: Car/Map bind atlandı");
            return;
        }
        bindCarService(sAppContext);
        bindSaicMapService(sAppContext);
    }

    public static boolean isReady() {
        return sCarPropertyManager != null;
    }

    public static VehicleSnapshot read() {
        if (sAppContext != null && HaSettings.demoMode(sAppContext)) {
            return DemoData.create();
        }

        VehicleSnapshot s = new VehicleSnapshot();
        s.capturedAtMs = System.currentTimeMillis();
        s.carConnected = sCarPropertyManager != null;

        s.socPercent = firstFloat(getFloat(PROP_SOC), bmsFloat(PROP_SOC));
        s.rangeKm = firstInt(getInt(PROP_RANGE), bmsInt(PROP_RANGE));
        s.odometerKm = getInt(PROP_TOTAL_MILEAGE);
        s.exteriorTempC = getSensorTemperature();

        s.tireKpaFl = getInt(PROP_TIRE_PRESSURE_FL);
        s.tireKpaFr = getInt(PROP_TIRE_PRESSURE_FR);
        s.tireKpaRl = getInt(PROP_TIRE_PRESSURE_RL);
        s.tireKpaRr = getInt(PROP_TIRE_PRESSURE_RR);

        s.chargeStatus = firstInt(getInt(PROP_CHG_STATUS), bmsInt(PROP_CHG_STATUS));
        s.batteryVoltageV = firstFloat(bmsFloat(PROP_BATT_VOLT), getFloat(PROP_BATT_VOLT));
        s.batteryCurrentA = firstFloat(bmsFloat(PROP_CHR_AMP_ACT), getFloat(PROP_CHR_AMP_ACT));
        s.acVoltageV = firstFloat(bmsFloat(PROP_AC_VOLT), getFloat(PROP_AC_VOLT));
        s.acCurrentA = firstFloat(bmsFloat(PROP_AC_AMP), getFloat(PROP_AC_AMP));
        float speedKmh = getFloat(PROP_SPEED);
        s.charging = isCharging(s.chargeStatus, s.acCurrentA, s.batteryCurrentA,
                s.batteryVoltageV, speedKmh);

        if (s.charging && !Float.isNaN(s.acVoltageV) && !Float.isNaN(s.acCurrentA) && s.acCurrentA > 0.5f) {
            s.chargePowerKw = (s.acVoltageV * s.acCurrentA) / 1000f;
        } else if (s.charging && !Float.isNaN(s.batteryVoltageV) && !Float.isNaN(s.batteryCurrentA)) {
            s.chargePowerKw = Math.abs(s.batteryVoltageV * s.batteryCurrentA) / 1000f;
        }
        fillGps(s);
        return s;
    }

    private static void fillGps(VehicleSnapshot s) {
        Context ctx = sAppContext;
        if (ctx == null) return;
        try {
            LocationManager lm = (LocationManager) ctx.getSystemService(Context.LOCATION_SERVICE);
            if (lm == null) return;
            Location loc = lm.getLastKnownLocation(LocationManager.GPS_PROVIDER);
            if (loc == null) loc = lm.getLastKnownLocation(LocationManager.NETWORK_PROVIDER);
            if (loc == null) loc = lm.getLastKnownLocation(LocationManager.PASSIVE_PROVIDER);
            if (loc == null) return;
            s.latitude = loc.getLatitude();
            s.longitude = loc.getLongitude();
            if (loc.hasAccuracy()) s.gpsAccuracyM = loc.getAccuracy();
        } catch (SecurityException e) {
            Log.w(TAG, "GPS izni yok: " + e.getMessage());
        } catch (Throwable t) {
            Log.w(TAG, "GPS okunamadı: " + t.getMessage());
        }
    }

    private static boolean isCharging(int status, float acAmp, float dcAmp, float battVolt, float speedKmh) {
        if (status == 1 || status == 10) return true;
        if (!Float.isNaN(acAmp) && acAmp > 0.5f) return true;
        return !Float.isNaN(dcAmp) && !Float.isNaN(battVolt)
                && battVolt > 200f && dcAmp <= -1f
                && (Float.isNaN(speedKmh) || speedKmh < 1f);
    }

    @SuppressWarnings("JavaReflectionMemberAccess")
    private static void bindCarService(Context context) {
        if (sCarBindAttempted) return;
        sCarBindAttempted = true;
        try {
            Class<?> carClass = Class.forName("android.car.Car");
            Method createCar = null;
            try {
                createCar = carClass.getMethod("createCar", Context.class);
            } catch (NoSuchMethodException ignored) {}
            Object car = null;
            if (createCar != null) {
                try {
                    car = createCar.invoke(null, context);
                } catch (Exception e) {
                    Log.w(TAG, "createCar(Context) hata: " + e.getMessage());
                }
            }
            if (car == null) {
                try {
                    Method createH = carClass.getMethod("createCar", Context.class, Handler.class);
                    car = createH.invoke(null, context, null);
                } catch (Exception e) {
                    Log.w(TAG, "createCar(Context,Handler) hata: " + e.getMessage());
                }
            }
            if (car == null) {
                Log.e(TAG, "Car.createCar başarısız");
                return;
            }
            sCar = car;
            try {
                Method connect = carClass.getMethod("connect");
                connect.invoke(car);
            } catch (NoSuchMethodException ignored) {
            } catch (Exception e) {
                Log.w(TAG, "car.connect: " + e.getMessage());
            }
            boolean connected = false;
            try {
                Method isConnected = carClass.getMethod("isConnected");
                connected = Boolean.TRUE.equals(isConnected.invoke(car));
            } catch (Exception ignored) {}
            if (connected) {
                tryGetManagers(carClass);
            } else {
                new Handler(Looper.getMainLooper()).postDelayed(() -> tryGetManagers(carClass), 500);
                new Handler(Looper.getMainLooper()).postDelayed(() -> tryGetManagers(carClass), 2500);
            }
        } catch (ClassNotFoundException e) {
            Log.e(TAG, "android.car.Car yok");
        } catch (Exception e) {
            Log.e(TAG, "bindCarService: " + e.getMessage());
        }
    }

    private static void tryGetManagers(Class<?> carClass) {
        if (sCar == null) return;
        try {
            Method getCarManager = carClass.getMethod("getCarManager", String.class);
            String propertyService = "property";
            try {
                propertyService = (String) carClass.getField("PROPERTY_SERVICE").get(null);
            } catch (Exception ignored) {}
            Object cpm = getCarManager.invoke(sCar, propertyService);
            if (cpm != null) {
                sCarPropertyManager = cpm;
                Log.i(TAG, "CarPropertyManager hazır");
            }
            try {
                Object bms = getCarManager.invoke(sCar, "bms");
                if (bms != null) {
                    registerBmsCallback(bms);
                    Log.i(TAG, "CarBMSManager hazır");
                }
            } catch (Exception e) {
                Log.w(TAG, "BMS manager yok: " + e.getMessage());
            }
        } catch (Exception e) {
            Log.e(TAG, "tryGetManagers: " + e.getMessage());
        }
    }

    private static void registerBmsCallback(Object bmsManager) {
        try {
            Method registerMethod = null;
            for (Method m : bmsManager.getClass().getMethods()) {
                String n = m.getName();
                if (n.contains("register") || n.contains("Register")) {
                    registerMethod = m;
                    break;
                }
            }
            if (registerMethod == null || registerMethod.getParameterTypes().length == 0) return;
            Class<?> callbackClass = registerMethod.getParameterTypes()[0];
            if (!callbackClass.isInterface()) return;
            Object proxy = Proxy.newProxyInstance(
                    callbackClass.getClassLoader(),
                    new Class<?>[]{callbackClass},
                    (proxyObj, method, args) -> {
                        cacheBmsArgs(args);
                        Class<?> ret = method.getReturnType();
                        if (ret == boolean.class) return false;
                        if (ret == int.class) return 0;
                        if (ret == long.class) return 0L;
                        if (ret == float.class) return 0f;
                        return null;
                    });
            registerMethod.invoke(bmsManager, proxy);
            Log.i(TAG, "BMS callback kayıtlı");
        } catch (Throwable t) {
            Log.w(TAG, "BMS callback: " + t.getMessage());
        }
    }

    private static void cacheBmsArgs(Object[] args) {
        if (args == null || args.length == 0) return;
        try {
            if (args.length >= 3 && args[0] instanceof Number && args[2] instanceof Number) {
                int propId = ((Number) args[0]).intValue();
                sBmsCache.put(propId, args[2]);
                return;
            }
            if (args.length == 1 && args[0] != null) {
                Object event = args[0];
                Method getPropId = findNoArg(event.getClass(), "getPropertyId", "getPropId");
                Method getVal = findNoArg(event.getClass(), "getValue", "getFloatValue");
                if (getPropId == null || getVal == null) return;
                Object pid = getPropId.invoke(event);
                Object val = getVal.invoke(event);
                if (pid instanceof Number && val instanceof Number) {
                    sBmsCache.put(((Number) pid).intValue(), val);
                }
            }
        } catch (Throwable ignored) {}
    }

    private static Method findNoArg(Class<?> clazz, String... names) {
        for (String n : names) {
            try {
                return clazz.getMethod(n);
            } catch (NoSuchMethodException ignored) {}
        }
        return null;
    }

    private static void bindSaicMapService(Context context) {
        if (sMapBindAttempted) return;
        sMapBindAttempted = true;
        try {
            Intent intent = new Intent();
            intent.setClassName(SAIC_MAP_PACKAGE, SAIC_MAP_SERVICE_CLASS);
            boolean ok = context.bindService(intent, sMapConnection, Context.BIND_AUTO_CREATE);
            Log.i(TAG, "MapService bind=" + ok);
        } catch (Throwable t) {
            Log.w(TAG, "MapService bind hata: " + t.getMessage());
        }
    }

    private static int getSensorTemperature() {
        IBinder b = sSaicMapBinder;
        if (b == null) return -1;
        Parcel data = Parcel.obtain();
        Parcel reply = Parcel.obtain();
        try {
            data.writeInterfaceToken(SAIC_MAP_DESCRIPTOR);
            if (!b.transact(TX_SAIC_MAP_GET_SENSOR_TEMPERATURE, data, reply, 0)) return -1;
            reply.readException();
            return reply.readInt();
        } catch (Throwable t) {
            return -1;
        } finally {
            data.recycle();
            reply.recycle();
        }
    }

    private static int getInt(int propId) {
        if (sCarPropertyManager == null) return -1;
        try {
            Method getProperty = sCarPropertyManager.getClass()
                    .getMethod("getProperty", Class.class, int.class, int.class);
            Object cpv = getProperty.invoke(sCarPropertyManager, Integer.class, propId, AREA_GLOBAL);
            if (cpv == null) return -1;
            Object v = cpv.getClass().getMethod("getValue").invoke(cpv);
            return v instanceof Number ? ((Number) v).intValue() : -1;
        } catch (Throwable t) {
            return -1;
        }
    }

    private static float getFloat(int propId) {
        if (sCarPropertyManager == null) return Float.NaN;
        try {
            Method getProperty = sCarPropertyManager.getClass()
                    .getMethod("getProperty", Class.class, int.class, int.class);
            Object cpv = getProperty.invoke(sCarPropertyManager, Float.class, propId, AREA_GLOBAL);
            if (cpv == null) return Float.NaN;
            Object v = cpv.getClass().getMethod("getValue").invoke(cpv);
            return v instanceof Number ? ((Number) v).floatValue() : Float.NaN;
        } catch (Throwable t) {
            return Float.NaN;
        }
    }

    private static float bmsFloat(int propId) {
        Object val = sBmsCache.get(propId);
        return val instanceof Number ? ((Number) val).floatValue() : Float.NaN;
    }

    private static int bmsInt(int propId) {
        Object val = sBmsCache.get(propId);
        return val instanceof Number ? ((Number) val).intValue() : -1;
    }

    private static float firstFloat(float a, float b) {
        return !Float.isNaN(a) ? a : b;
    }

    private static int firstInt(int a, int b) {
        return a >= 0 ? a : b;
    }
}
