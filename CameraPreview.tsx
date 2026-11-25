import React, {
    useCallback,
    useEffect,
    useMemo,
    useRef,
    useState,
  } from 'react';
  import {
    AppState,
    AppStateStatus,
    NativeSyntheticEvent,
    PermissionsAndroid,
    Platform,
    StyleProp,
    UIManager,
    ViewStyle,
    requireNativeComponent,
  } from 'react-native';
  
  export type MarkDetectedEvent = {
    count: number;
    darkRatio: number;
  };
  
  export type CameraErrorReason = 'permissionDenied' | 'initFailed';
  
  export type CameraErrorEvent = {
    reason: CameraErrorReason;
    message: string;
  };
  
  export type CameraPreviewProps = {
    style?: StyleProp<ViewStyle>;
    lineDarkThreshold?: number;
    lineCoverageThreshold?: number;
    movementGateRatio?: number;
    requiredDetectFrames?: number;
    requiredClearFrames?: number;
    detectLightLines?: boolean;
    analyzing?: boolean;
    onMarkDetected?: (event: NativeSyntheticEvent<MarkDetectedEvent>) => void;
    onCameraError?: (event: NativeSyntheticEvent<CameraErrorEvent>) => void;
    onPermissionResult?: (granted: boolean) => void;
    resetSignal?: number;
  };
  
  const LINKING_ERROR =
    `Le composant natif 'CameraPreview' est introuvable.\n` +
    `Vérifie que CameraPreviewPackage est ajouté dans MainApplication.kt ` +
    `et que l'app Android a été rebuildée.`;
  
  const NativeCameraPreview =
    UIManager.getViewManagerConfig?.('CameraPreview') != null
      ? requireNativeComponent<CameraPreviewProps>('CameraPreview')
      : () => {
          throw new Error(LINKING_ERROR);
        };
  
  async function requestCameraPermission(): Promise<boolean> {
    if (Platform.OS !== 'android') return true;
    const result = await PermissionsAndroid.request(
      PermissionsAndroid.PERMISSIONS.CAMERA,
      {
        title: 'Accès caméra',
        message:
          'PrintCounter a besoin de la caméra pour compter les lignes imprimées.',
        buttonPositive: 'Autoriser',
        buttonNegative: 'Refuser',
      },
    );
    return result === PermissionsAndroid.RESULTS.GRANTED;
  }
  
  async function hasCameraPermission(): Promise<boolean> {
    if (Platform.OS !== 'android') return true;
    return PermissionsAndroid.check(PermissionsAndroid.PERMISSIONS.CAMERA);
  }
  
  export const CameraPreview: React.FC<CameraPreviewProps> = ({
    style,
    lineDarkThreshold = 75,
    lineCoverageThreshold = 0.35,
    movementGateRatio = 0.2,
    requiredDetectFrames = 2,
    requiredClearFrames = 5,
    detectLightLines = false,
    analyzing = true,
    onMarkDetected,
    onCameraError,
    onPermissionResult,
    resetSignal = 0,
  }) => {
    const [retryTrigger, setRetryTrigger] = useState(0);
    const permissionRequestedRef = useRef(false);
  
    useEffect(() => {
      let cancelled = false;
      (async () => {
        if (permissionRequestedRef.current) return;
        permissionRequestedRef.current = true;
  
        const already = await hasCameraPermission();
        if (already) {
          onPermissionResult?.(true);
          return;
        }
        const granted = await requestCameraPermission();
        if (cancelled) return;
        onPermissionResult?.(granted);
        if (granted) setRetryTrigger(t => t + 1);
      })();
      return () => {
        cancelled = true;
      };
    }, [onPermissionResult]);
  
    useEffect(() => {
      const onChange = async (state: AppStateStatus) => {
        if (state !== 'active') return;
        const granted = await hasCameraPermission();
        if (granted) setRetryTrigger(t => t + 1);
      };
      const sub = AppState.addEventListener('change', onChange);
      return () => sub.remove();
    }, []);
  
    const handleCameraError = useCallback(
      (event: NativeSyntheticEvent<CameraErrorEvent>) => {
        const { reason } = event.nativeEvent;
        if (reason === 'permissionDenied') {
          (async () => {
            const granted = await requestCameraPermission();
            onPermissionResult?.(granted);
            if (granted) setRetryTrigger(t => t + 1);
          })();
        }
        onCameraError?.(event);
      },
      [onCameraError, onPermissionResult],
    );
  
    const combinedRetry = useMemo(
      () => retryTrigger + resetSignal,
      [retryTrigger, resetSignal],
    );
  
    return (
      <NativeCameraPreview
        style={style}
        lineDarkThreshold={lineDarkThreshold}
        lineCoverageThreshold={lineCoverageThreshold}
        movementGateRatio={movementGateRatio}
        requiredDetectFrames={requiredDetectFrames}
        requiredClearFrames={requiredClearFrames}
        detectLightLines={detectLightLines}
        analyzing={analyzing}
        retryTrigger={combinedRetry}
        resetTrigger={resetSignal}
        onMarkDetected={onMarkDetected}
        onCameraError={handleCameraError}
      />
    );
  };
  
  export default CameraPreview;