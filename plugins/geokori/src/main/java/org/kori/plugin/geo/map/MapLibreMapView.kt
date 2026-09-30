package org.kori.plugin.geo.map

import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import org.maplibre.android.MapLibre
import org.maplibre.android.camera.CameraPosition
import org.maplibre.android.geometry.LatLng
import org.maplibre.android.maps.MapView
import org.maplibre.android.maps.Style

@Composable
fun MapLibreMapView(
    modifier: Modifier = Modifier,
    styleUrl: String = MapStyle.LTIANDITU.uri,
) {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current

    // 记住 MapView 实例，避免重组时重复创建
    val mapView = remember {
        MapLibre.getInstance(context)
        MapView(context).apply {
            onCreate(null) // savedInstanceState 可传 null
        }
    }

    // 将 Compose 的生命周期转发给 MapView
    DisposableEffect(lifecycleOwner, mapView) {
        val observer = LifecycleEventObserver { _, event ->
            when (event) {
                Lifecycle.Event.ON_START -> mapView.onStart()
                Lifecycle.Event.ON_RESUME -> mapView.onResume()
                Lifecycle.Event.ON_PAUSE -> mapView.onPause()
                Lifecycle.Event.ON_STOP -> mapView.onStop()
                Lifecycle.Event.ON_DESTROY -> mapView.onDestroy()
                else -> Unit
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose {
            lifecycleOwner.lifecycle.removeObserver(observer)
            mapView.onStop()
            mapView.onDestroy()
        }
    }
    // 设置样式与初始相机位置
    LaunchedEffect(mapView) {
        mapView.getMapAsync { map ->
            map.setStyle(Style.Builder().fromUri(styleUrl)) {
                // 样式加载完成后的回调
                map.cameraPosition = CameraPosition.Builder()
                    .target(LatLng(39.909, 116.397)) // 北京
                    .zoom(5.0)
                    .build()
            }
        }
    }

    AndroidView(
        factory = { mapView },
        modifier = modifier,
    )
}