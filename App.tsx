
import React, { useEffect, useState } from 'react';

import {
  SafeAreaView,
  View,
  Text,
  StyleSheet,
  StatusBar,
  TouchableOpacity,
  PermissionsAndroid,
  Platform,
  requireNativeComponent,
  ViewProps,
} from 'react-native';

const NativeCameraPreview =
  requireNativeComponent<ViewProps>('CameraPreview');

function App() {
  const [cameraGranted, setCameraGranted] = useState(false);
  const [permissionMessage, setPermissionMessage] = useState(
    'Waiting for camera permission'
  );

  const requestCameraPermission = async () => {
    if (Platform.OS !== 'android') {
      setPermissionMessage('Android only');
      return;
    }

    try {
      const result = await PermissionsAndroid.request(
        PermissionsAndroid.PERMISSIONS.CAMERA,
        {
          title: 'PrintCounter Camera Permission',
          message:
            'PrintCounter needs camera access to detect label separation marks.',
          buttonPositive: 'Allow',
          buttonNegative: 'Cancel',
        }
      );

      if (result === PermissionsAndroid.RESULTS.GRANTED) {
        setCameraGranted(true);
        setPermissionMessage('Camera connected');
      } else {
        setCameraGranted(false);
        setPermissionMessage('Camera permission denied');
      }
    } catch (error) {
      setCameraGranted(false);
      setPermissionMessage('Camera permission error');
    }
  };

  useEffect(() => {
    requestCameraPermission();
  }, []);

  return (
    <SafeAreaView style={styles.container}>
      <StatusBar
        backgroundColor="#101827"
        barStyle="light-content"
      />

      <View style={styles.header}>
        <Text style={styles.logo}>PrintCounter</Text>
        <Text style={styles.subtitle}>
          Smart Label Counting System
        </Text>
      </View>

      <View style={styles.statusCard}>
        <View
          style={[
            styles.statusDot,
            {
              backgroundColor: cameraGranted
                ? '#22C55E'
                : '#EAB308',
            },
          ]}
        />

        <Text
          style={[
            styles.statusText,
            {
              color: cameraGranted ? '#22C55E' : '#EAB308',
            },
          ]}
        >
          {cameraGranted ? 'Camera Ready' : 'Camera Setup'}
        </Text>
      </View>

      <View style={styles.counterCard}>
        <Text style={styles.label}>
          CURRENT ROLL COUNT
        </Text>

        <Text style={styles.counter}>0</Text>

        <Text style={styles.unit}>
          Units detected
        </Text>
      </View>

      <View style={styles.cameraCard}>
        <Text style={styles.cameraTitle}>
          CAMERA PREVIEW
        </Text>

        <View style={styles.cameraPlaceholder}>
          {cameraGranted ? (
            <NativeCameraPreview
              style={styles.cameraPreview}
            />
          ) : (
            <View style={styles.permissionBox}>
              <Text style={styles.cameraIcon}>◎</Text>

              <Text style={styles.cameraMessage}>
                {permissionMessage}
              </Text>
            </View>
          )}
        </View>
      </View>

      <TouchableOpacity
        style={[
          styles.startButton,
          cameraGranted && styles.readyButton,
        ]}
        onPress={requestCameraPermission}
        disabled={cameraGranted}
      >
        <Text style={styles.startText}>
          {cameraGranted
            ? 'CAMERA PREVIEW ACTIVE'
            : 'ENABLE CAMERA'}
        </Text>
      </TouchableOpacity>

      <View style={styles.footer}>
        <Text style={styles.footerText}>
          PrintCounter v0.2.0
        </Text>
      </View>
    </SafeAreaView>
  );
}

const styles = StyleSheet.create({
  container: {
    flex: 1,
    backgroundColor: '#101827',
    padding: 20,
  },

  header: {
    marginTop: 20,
    marginBottom: 25,
  },

  logo: {
    fontSize: 28,
    fontWeight: 'bold',
    color: '#FFFFFF',
  },

  subtitle: {
    fontSize: 13,
    color: '#94A3B8',
    marginTop: 5,
  },

  statusCard: {
    flexDirection: 'row',
    alignItems: 'center',
    backgroundColor: '#1E293B',
    padding: 14,
    borderRadius: 12,
  },

  statusDot: {
    width: 9,
    height: 9,
    borderRadius: 5,
    marginRight: 10,
  },

  statusText: {
    fontWeight: '600',
  },

  counterCard: {
    backgroundColor: '#1E293B',
    marginTop: 20,
    padding: 25,
    borderRadius: 18,
    alignItems: 'center',
  },

  label: {
    color: '#94A3B8',
    fontSize: 12,
    fontWeight: '600',
    letterSpacing: 1,
  },

  counter: {
    color: '#FFFFFF',
    fontSize: 64,
    fontWeight: 'bold',
    marginVertical: 10,
  },

  unit: {
    color: '#94A3B8',
    fontSize: 13,
  },

  cameraCard: {
    backgroundColor: '#1E293B',
    marginTop: 20,
    padding: 15,
    borderRadius: 18,
    flex: 1,
  },

  cameraTitle: {
    color: '#CBD5E1',
    fontWeight: '600',
    marginBottom: 12,
  },

  cameraPlaceholder: {
    flex: 1,
    backgroundColor: '#0F172A',
    borderRadius: 12,
    overflow: 'hidden',
  },

  cameraPreview: {
    flex: 1,
    width: '100%',
  },

  permissionBox: {
    flex: 1,
    alignItems: 'center',
    justifyContent: 'center',
  },

  cameraIcon: {
    color: '#64748B',
    fontSize: 45,
  },

  cameraMessage: {
    color: '#94A3B8',
    marginTop: 10,
    textAlign: 'center',
  },

  startButton: {
    backgroundColor: '#16A34A',
    padding: 18,
    borderRadius: 14,
    alignItems: 'center',
    marginTop: 20,
  },

  readyButton: {
    backgroundColor: '#334155',
  },

  startText: {
    color: '#FFFFFF',
    fontSize: 15,
    fontWeight: 'bold',
  },

  footer: {
    alignItems: 'center',
    marginTop: 15,
  },

  footerText: {
    color: '#64748B',
    fontSize: 11,
  },
});

export default App;