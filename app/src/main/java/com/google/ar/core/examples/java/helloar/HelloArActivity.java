/*
 * Copyright 2017 Google LLC
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.google.ar.core.examples.java.helloar;

import android.media.Image;
import android.opengl.GLES30;
import android.opengl.GLSurfaceView;
import android.opengl.Matrix;
import android.os.Bundle;
import android.util.Log;

import android.view.View;
import android.widget.ImageButton;

import android.widget.Toast;

import androidx.appcompat.app.AppCompatActivity;
import com.google.ar.core.Anchor;
import com.google.ar.core.ArCoreApk;
import com.google.ar.core.Camera;
import com.google.ar.core.Config;
import com.google.ar.core.Config.InstantPlacementMode;
import com.google.ar.core.Frame;
import com.google.ar.core.InstantPlacementPoint;
import com.google.ar.core.LightEstimate;
import com.google.ar.core.Plane;
import com.google.ar.core.PointCloud;
import com.google.ar.core.Pose;
import com.google.ar.core.Session;
import com.google.ar.core.Trackable;
import com.google.ar.core.TrackingFailureReason;
import com.google.ar.core.TrackingState;
import com.google.ar.core.examples.java.common.helpers.CameraPermissionHelper;
import com.google.ar.core.examples.java.common.helpers.DepthSettings;
import com.google.ar.core.examples.java.common.helpers.DisplayRotationHelper;
import com.google.ar.core.examples.java.common.helpers.FullScreenHelper;
import com.google.ar.core.examples.java.common.helpers.InstantPlacementSettings;
import com.google.ar.core.examples.java.common.helpers.SnackbarHelper;
import com.google.ar.core.examples.java.common.helpers.TapHelper;
import com.google.ar.core.examples.java.common.helpers.TrackingStateHelper;
import com.google.ar.core.examples.java.common.samplerender.Framebuffer;
import com.google.ar.core.examples.java.common.samplerender.GLError;
import com.google.ar.core.examples.java.common.samplerender.Mesh;
import com.google.ar.core.examples.java.common.samplerender.SampleRender;
import com.google.ar.core.examples.java.common.samplerender.Shader;
import com.google.ar.core.examples.java.common.samplerender.Texture;
import com.google.ar.core.examples.java.common.samplerender.VertexBuffer;
import com.google.ar.core.examples.java.common.samplerender.arcore.BackgroundRenderer;
import com.google.ar.core.examples.java.common.samplerender.arcore.PlaneRenderer;
import com.google.ar.core.examples.java.common.samplerender.arcore.SpecularCubemapFilter;
import com.google.ar.core.exceptions.CameraNotAvailableException;
import com.google.ar.core.exceptions.NotYetAvailableException;
import com.google.ar.core.exceptions.UnavailableApkTooOldException;
import com.google.ar.core.exceptions.UnavailableArcoreNotInstalledException;
import com.google.ar.core.exceptions.UnavailableDeviceNotCompatibleException;
import com.google.ar.core.exceptions.UnavailableSdkTooOldException;
import com.google.ar.core.exceptions.UnavailableUserDeclinedInstallationException;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.List;


/**
 * This is a simple example that shows how to create an augmented reality (AR) application using the
 * ARCore API. The application will display any detected planes.
 */
public class HelloArActivity extends AppCompatActivity implements SampleRender.Renderer {

  private static final String TAG = HelloArActivity.class.getSimpleName();

  private static final String SEARCHING_PLANE_MESSAGE = "Recherche de surfaces...";
  private static final String WAITING_FOR_TAP_MESSAGE = "Appuyer sur l'enveloppe pour envoyer les données.";
  private static final String COLLECTION_PLANE_MESSAGE = "Tous les plans existant ont été collecté.";

  // See the definition of updateSphericalHarmonicsCoefficients for an explanation of these
  // constants.
  private static final float[] sphericalHarmonicFactors = {
    0.282095f,
    -0.325735f,
    0.325735f,
    -0.325735f,
    0.273137f,
    -0.273137f,
    0.078848f,
    -0.273137f,
    0.136569f,
  };

  private static final float Z_NEAR = 0.1f;
  private static final float Z_FAR = 100f;

  private static final int CUBEMAP_RESOLUTION = 16;
  private static final int CUBEMAP_NUMBER_OF_IMPORTANCE_SAMPLES = 32;

  // Rendering. The Renderers are created here, and initialized when the GL surface is created.
  private GLSurfaceView surfaceView;

  private boolean installRequested;

  private Session session;
  private final SnackbarHelper messageSnackbarHelper = new SnackbarHelper();
  private DisplayRotationHelper displayRotationHelper;
  private final TrackingStateHelper trackingStateHelper = new TrackingStateHelper(this);
  private TapHelper tapHelper;
  private SampleRender render;

  private PlaneRenderer planeRenderer;
  private BackgroundRenderer backgroundRenderer;
  private Framebuffer virtualSceneFramebuffer;
  private boolean hasSetTextureNames = false;

  private final DepthSettings depthSettings = new DepthSettings();
  private final InstantPlacementSettings instantPlacementSettings = new InstantPlacementSettings();

  // Assumed distance from the device camera to the surface on which user will try to place objects.
  // This value affects the apparent scale of objects while the tracking method of the
  // Instant Placement point is SCREENSPACE_WITH_APPROXIMATE_DISTANCE.
  // Values in the [0.2, 2.0] meter range are a good choice for most AR experiences. Use lower
  // values for AR experiences where users are expected to place objects on surfaces close to the
  // camera. Use larger values for experiences where the user will likely be standing and trying to
  // place an object on the ground or floor in front of them.
  private static final float APPROXIMATE_DISTANCE_METERS = 2.0f;

  // Point Cloud
  private VertexBuffer pointCloudVertexBuffer;
  private Mesh pointCloudMesh;
  private Shader pointCloudShader;
  // Keep track of the last point cloud rendered to avoid updating the VBO if point cloud
  // was not changed.  Do this using the timestamp since we can't compare PointCloud objects.
  private long lastPointCloudTimestamp = 0;

  // Virtual object (ARCore pawn)
  private Mesh virtualObjectMesh;
  private Shader virtualObjectShader;
  private Texture virtualObjectAlbedoTexture;
  private Texture virtualObjectAlbedoInstantPlacementTexture;

  private final List<WrappedAnchor> wrappedAnchors = new ArrayList<>();

  // Environmental HDR
  private Texture dfgTexture;
  private SpecularCubemapFilter cubemapFilter;

  // Temporary matrix allocated here to reduce number of allocations for each frame.
  private final float[] modelMatrix = new float[16];
  private final float[] viewMatrix = new float[16];
  private final float[] projectionMatrix = new float[16];
  private final float[] modelViewMatrix = new float[16]; // view x model
  private final float[] modelViewProjectionMatrix = new float[16]; // projection x view x model
  private final float[] sphericalHarmonicsCoefficients = new float[9 * 3];
  private final float[] viewInverseMatrix = new float[16];
  private final float[] worldLightDirection = {0.0f, 0.0f, 0.0f, 0.0f};
  private final float[] viewLightDirection = new float[4]; // view x world light direction

  // Model ID qui sera envoyé au serveur
  private int model_id;

  @Override
  protected void onCreate(Bundle savedInstanceState) {
    super.onCreate(savedInstanceState);
    setContentView(R.layout.activity_main);
    surfaceView = findViewById(R.id.surfaceview);
    displayRotationHelper = new DisplayRotationHelper(/*context=*/ this);

    // Set up touch listener.
    tapHelper = new TapHelper(/*context=*/ this);
    surfaceView.setOnTouchListener(tapHelper);

    // Set up renderer.
    render = new SampleRender(surfaceView, this, getAssets());

    installRequested = false;

    //Initialisation à 0 du model_id
    model_id = 0;


    ImageButton sendButton = findViewById(R.id.send_button);
    sendButton.setOnClickListener(
            new View.OnClickListener() {
              @Override
              public void onClick(View v) {
                Collection<Plane> allPlanes = getCollectionOfAllPlanes();
                messageSnackbarHelper.showMessage(HelloArActivity.this,
                        COLLECTION_PLANE_MESSAGE);
                model_id++;
                try {
                  fromPlanesToJSON(allPlanes);
                } catch (IOException e) {
                  e.printStackTrace();
                }
              }
            });
  }

  protected Collection<Plane> getCollectionOfAllPlanes() {
    Collection<Plane> allPlanes = session.getAllTrackables(Plane.class);
    return allPlanes;
  }

  protected void fromPlanesToJSON (Collection<Plane> p) throws IOException {

    //construction de l'en-tête du json
    String model_id_str = String.valueOf(model_id);
    String json = "{" + "\"model\": " + model_id_str + "," + "\"Planes\":[";

    Pose centerPose = null;
    //ID du plan traité (0 avant de parcourir les plans)
    int idP = 0;
    //ID des points
    int idp = 1;

    float[] point1 = new float[3];
    float[] point2 = new float[3];
    float[] point3 = new float[3];
    float[] point4 = new float[3];

    //Pour chaque plan
    for(Plane plan_i : p){

      idP++;
      centerPose = plan_i.getCenterPose();
      point1 = centerPose.transformPoint(new float[]{(plan_i.getExtentX())/2, 0, (plan_i.getExtentZ())/2});
      point2 = centerPose.transformPoint(new float[]{(plan_i.getExtentX())/2, 0, -(plan_i.getExtentZ())/2});
      point3 = centerPose.transformPoint(new float[]{-(plan_i.getExtentX())/2, 0, (plan_i.getExtentZ())/2});
      point4 = centerPose.transformPoint(new float[]{-(plan_i.getExtentX())/2, 0, -(plan_i.getExtentZ())/2});

      if (idP == 1)
        json = json + "{\"idP\":" + String.valueOf(idP) + ",\"Points\":[";
      else
        json = json + ",{\"idP\": " + String.valueOf(idP) + ",\"Points\": [";

      //ici récupération du premier point
      json = json + "{\"idp\":" + idp + ",";
      idp++;
        //vx, vy, vz
        json = json + "\"vx\":" + String.valueOf(point1[0]) + ",";
        json = json + "\"vy\":" + String.valueOf(point1[1]) + ",";
        json = json + "\"vz\":" + String.valueOf(point1[2]) + ",";
        // le reste qui de tout façon est à zero
        json = json + "\"vnx\":0,\"vny\":0,\"vnz\":0},";

      //ici récupération du deuxième point
      json = json + "{\"idp\":" + idp + ",";
      idp++;
        //vx, vy, vz
        json = json + "\"vx\":" + String.valueOf(point2[0]) + ",";
        json = json + "\"vy\":" + String.valueOf(point2[1]) + ",";
        json = json + "\"vz\":" + String.valueOf(point2[2]) + ",";
        // le reste qui de tout façon est à zero
        json = json + "\"vnx\":0,\"vny\":0,\"vnz\":0},";

      //ici récupération du troisième point
      json = json + "{\"idp\":" + idp + ",";
      idp++;
        //vx, vy, vz
        json = json + "\"vx\":" + String.valueOf(point3[0]) + ",";
        json = json + "\"vy\":" + String.valueOf(point3[1]) + ",";
        json = json + "\"vz\":" + String.valueOf(point3[2]) + ",";
        // le reste qui de tout façon est à zero
        json = json + "\"vnx\":0,\"vny\":0,\"vnz\":0},";

      //ici récupération du quatrième point
      json = json + "{\"idp\":" + idp + ",";
      idp++;
        //vx, vy, vz
        json = json + "\"vx\":" + String.valueOf(point4[0]) + ",";
        json = json + "\"vy\":" + String.valueOf(point4[1]) + ",";
        json = json + "\"vz\":" + String.valueOf(point4[2]) + ",";
        // le reste qui de tout façon est à zero
        json = json + "\"vnx\":0,\"vny\":0,\"vnz\":0}]}";
    }
    json = json + "]}";
    sendToServer(json);
  }

  protected void sendToServer(String s) throws IOException {
    URL url = new URL ("http://ip:port/api/v1/parsePlanes");
    HttpURLConnection con = (HttpURLConnection)url.openConnection();
    con.setRequestMethod("POST");
    con.setRequestProperty("Content-Type", "application/string; utf-8");
    con.setDoOutput(true);

    try(OutputStream os = con.getOutputStream()) {
      byte[] input = s.getBytes(StandardCharsets.UTF_8);
      os.write(input, 0, input.length);
      generateModel();
    }
  }

  protected void generateModel()  throws IOException {
    URL url = new URL ("http://ip:port/api/v1/createModel");
    HttpURLConnection con = (HttpURLConnection)url.openConnection();
    con.setRequestMethod("POST");
    con.setRequestProperty("Content-Type", "application/string; utf-8");
    con.setDoOutput(true);

    try(OutputStream os = con.getOutputStream()) {
      byte[] input = String.valueOf(model_id).getBytes(StandardCharsets.UTF_8);
      os.write(input, 0, input.length);
    }
  }


  @Override
  protected void onDestroy() {
    if (session != null) {
      // Explicitly close ARCore Session to release native resources.
      // Review the API reference for important considerations before calling close() in apps with
      // more complicated lifecycle requirements:
      // https://developers.google.com/ar/reference/java/arcore/reference/com/google/ar/core/Session#close()
      session.close();
      session = null;
    }

    super.onDestroy();
  }

  @Override
  protected void onResume() {
    super.onResume();

    if (session == null) {
      Exception exception = null;
      String message = null;
      try {
        switch (ArCoreApk.getInstance().requestInstall(this, !installRequested)) {
          case INSTALL_REQUESTED:
            installRequested = true;
            return;
          case INSTALLED:
            break;
        }

        // ARCore requires camera permissions to operate. If we did not yet obtain runtime
        // permission on Android M and above, now is a good time to ask the user for it.
        if (!CameraPermissionHelper.hasCameraPermission(this)) {
          CameraPermissionHelper.requestCameraPermission(this);
          return;
        }

        // Create the session.
        session = new Session(/* context= */ this);
      } catch (UnavailableArcoreNotInstalledException
          | UnavailableUserDeclinedInstallationException e) {
        message = "Please install ARCore";
        exception = e;
      } catch (UnavailableApkTooOldException e) {
        message = "Please update ARCore";
        exception = e;
      } catch (UnavailableSdkTooOldException e) {
        message = "Please update this app";
        exception = e;
      } catch (UnavailableDeviceNotCompatibleException e) {
        message = "This device does not support AR";
        exception = e;
      } catch (Exception e) {
        message = "Failed to create AR session";
        exception = e;
      }

      if (message != null) {
        messageSnackbarHelper.showError(this, message);
        Log.e(TAG, "Exception creating session", exception);
        return;
      }
    }

    // Note that order matters - see the note in onPause(), the reverse applies here.
    try {
      configureSession();
      // To record a live camera session for later playback, call
      // `session.startRecording(recordingConfig)` at anytime. To playback a previously recorded AR
      // session instead of using the live camera feed, call
      // `session.setPlaybackDatasetUri(Uri)` before calling `session.resume()`. To
      // learn more about recording and playback, see:
      // https://developers.google.com/ar/develop/java/recording-and-playback
      session.resume();
    } catch (CameraNotAvailableException e) {
      messageSnackbarHelper.showError(this, "Camera not available. Try restarting the app.");
      session = null;
      return;
    }

    surfaceView.onResume();
    displayRotationHelper.onResume();
  }

  @Override
  public void onPause() {
    super.onPause();
    if (session != null) {
      // Note that the order matters - GLSurfaceView is paused first so that it does not try
      // to query the session. If Session is paused before GLSurfaceView, GLSurfaceView may
      // still call session.update() and get a SessionPausedException.
      displayRotationHelper.onPause();
      surfaceView.onPause();
      session.pause();
    }
  }

  @Override
  public void onRequestPermissionsResult(int requestCode, String[] permissions, int[] results) {
    super.onRequestPermissionsResult(requestCode, permissions, results);
    if (!CameraPermissionHelper.hasCameraPermission(this)) {
      // Use toast instead of snackbar here since the activity will exit.
      Toast.makeText(this, "Camera permission is needed to run this application", Toast.LENGTH_LONG)
          .show();
      if (!CameraPermissionHelper.shouldShowRequestPermissionRationale(this)) {
        // Permission denied with checking "Do not ask again".
        CameraPermissionHelper.launchPermissionSettings(this);
      }
      finish();
    }
  }

  @Override
  public void onWindowFocusChanged(boolean hasFocus) {
    super.onWindowFocusChanged(hasFocus);
    FullScreenHelper.setFullScreenOnWindowFocusChanged(this, hasFocus);
  }

  @Override
  public void onSurfaceCreated(SampleRender render) {
    // Prepare the rendering objects. This involves reading shaders and 3D model files, so may throw
    // an IOException.
    try {
      planeRenderer = new PlaneRenderer(render);
      backgroundRenderer = new BackgroundRenderer(render);
      virtualSceneFramebuffer = new Framebuffer(render, /*width=*/ 1, /*height=*/ 1);

      cubemapFilter =
          new SpecularCubemapFilter(
              render, CUBEMAP_RESOLUTION, CUBEMAP_NUMBER_OF_IMPORTANCE_SAMPLES);
      // Load DFG lookup table for environmental lighting
      dfgTexture =
          new Texture(
              render,
              Texture.Target.TEXTURE_2D,
              Texture.WrapMode.CLAMP_TO_EDGE,
              /*useMipmaps=*/ false);
      // The dfg.raw file is a raw half-float texture with two channels.
      final int dfgResolution = 64;
      final int dfgChannels = 2;
      final int halfFloatSize = 2;

      ByteBuffer buffer =
          ByteBuffer.allocateDirect(dfgResolution * dfgResolution * dfgChannels * halfFloatSize);
      try (InputStream is = getAssets().open("models/dfg.raw")) {
        is.read(buffer.array());
      }
      // SampleRender abstraction leaks here.
      GLES30.glBindTexture(GLES30.GL_TEXTURE_2D, dfgTexture.getTextureId());
      GLError.maybeThrowGLException("Failed to bind DFG texture", "glBindTexture");
      GLES30.glTexImage2D(
          GLES30.GL_TEXTURE_2D,
          /*level=*/ 0,
          GLES30.GL_RG16F,
          /*width=*/ dfgResolution,
          /*height=*/ dfgResolution,
          /*border=*/ 0,
          GLES30.GL_RG,
          GLES30.GL_HALF_FLOAT,
          buffer);
      GLError.maybeThrowGLException("Failed to populate DFG texture", "glTexImage2D");

      // Point cloud
      pointCloudShader =
          Shader.createFromAssets(
                  render, "shaders/point_cloud.vert", "shaders/point_cloud.frag", /*defines=*/ null)
              .setVec4(
                  "u_Color", new float[] {31.0f / 255.0f, 188.0f / 255.0f, 210.0f / 255.0f, 1.0f})
              .setFloat("u_PointSize", 5.0f);
      // four entries per vertex: X, Y, Z, confidence
      pointCloudVertexBuffer =
          new VertexBuffer(render, /*numberOfEntriesPerVertex=*/ 4, /*entries=*/ null);
      final VertexBuffer[] pointCloudVertexBuffers = {pointCloudVertexBuffer};
      pointCloudMesh =
          new Mesh(
              render, Mesh.PrimitiveMode.POINTS, /*indexBuffer=*/ null, pointCloudVertexBuffers);

      // Virtual object to render (ARCore pawn)
      virtualObjectAlbedoTexture =
          Texture.createFromAsset(
              render,
              "models/pawn_albedo.png",
              Texture.WrapMode.CLAMP_TO_EDGE,
              Texture.ColorFormat.SRGB);
      virtualObjectAlbedoInstantPlacementTexture =
          Texture.createFromAsset(
              render,
              "models/pawn_albedo_instant_placement.png",
              Texture.WrapMode.CLAMP_TO_EDGE,
              Texture.ColorFormat.SRGB);
      Texture virtualObjectPbrTexture =
          Texture.createFromAsset(
              render,
              "models/pawn_roughness_metallic_ao.png",
              Texture.WrapMode.CLAMP_TO_EDGE,
              Texture.ColorFormat.LINEAR);

      virtualObjectMesh = Mesh.createFromAsset(render, "models/pawn.obj");
      virtualObjectShader =
          Shader.createFromAssets(
                  render,
                  "shaders/environmental_hdr.vert",
                  "shaders/environmental_hdr.frag",
                  /*defines=*/ new HashMap<String, String>() {
                    {
                      put(
                          "NUMBER_OF_MIPMAP_LEVELS",
                          Integer.toString(cubemapFilter.getNumberOfMipmapLevels()));
                    }
                  })
              .setTexture("u_AlbedoTexture", virtualObjectAlbedoTexture)
              .setTexture("u_RoughnessMetallicAmbientOcclusionTexture", virtualObjectPbrTexture)
              .setTexture("u_Cubemap", cubemapFilter.getFilteredCubemapTexture())
              .setTexture("u_DfgTexture", dfgTexture);
    } catch (IOException e) {
      Log.e(TAG, "Failed to read a required asset file", e);
      messageSnackbarHelper.showError(this, "Failed to read a required asset file: " + e);
    }
  }

  @Override
  public void onSurfaceChanged(SampleRender render, int width, int height) {
    displayRotationHelper.onSurfaceChanged(width, height);
    virtualSceneFramebuffer.resize(width, height);
  }

  @Override
  public void onDrawFrame(SampleRender render) {
    if (session == null) {
      return;
    }

    // Texture names should only be set once on a GL thread unless they change. This is done during
    // onDrawFrame rather than onSurfaceCreated since the session is not guaranteed to have been
    // initialized during the execution of onSurfaceCreated.
    if (!hasSetTextureNames) {
      session.setCameraTextureNames(
          new int[] {backgroundRenderer.getCameraColorTexture().getTextureId()});
      hasSetTextureNames = true;
    }

    // -- Update per-frame state

    // Notify ARCore session that the view size changed so that the perspective matrix and
    // the video background can be properly adjusted.
    displayRotationHelper.updateSessionIfNeeded(session);

    // Obtain the current frame from ARSession. When the configuration is set to
    // UpdateMode.BLOCKING (it is by default), this will throttle the rendering to the
    // camera framerate.
    Frame frame;
    try {
      frame = session.update();
    } catch (CameraNotAvailableException e) {
      Log.e(TAG, "Camera not available during onDrawFrame", e);
      messageSnackbarHelper.showError(this, "Camera not available. Try restarting the app.");
      return;
    }
    Camera camera = frame.getCamera();

    // Update BackgroundRenderer state to match the depth settings.
    try {
      backgroundRenderer.setUseDepthVisualization(
          render, depthSettings.depthColorVisualizationEnabled());
      backgroundRenderer.setUseOcclusion(render, depthSettings.useDepthForOcclusion());
    } catch (IOException e) {
      Log.e(TAG, "Failed to read a required asset file", e);
      messageSnackbarHelper.showError(this, "Failed to read a required asset file: " + e);
      return;
    }
    // BackgroundRenderer.updateDisplayGeometry must be called every frame to update the coordinates
    // used to draw the background camera image.
    backgroundRenderer.updateDisplayGeometry(frame);

    if (camera.getTrackingState() == TrackingState.TRACKING
        && (depthSettings.useDepthForOcclusion()
            || depthSettings.depthColorVisualizationEnabled())) {
      try (Image depthImage = frame.acquireDepthImage()) {
        backgroundRenderer.updateCameraDepthTexture(depthImage);
      } catch (NotYetAvailableException e) {
        // This normally means that depth data is not available yet. This is normal so we will not
        // spam the logcat with this.
      }
    }

    // Handle one tap per frame.
    //handleTap(frame, camera);

    // Keep the screen unlocked while tracking, but allow it to lock when tracking stops.
    trackingStateHelper.updateKeepScreenOnFlag(camera.getTrackingState());

    // Show a message based on whether tracking has failed, if planes are detected, and if the user
    // has placed any objects.
    String message = null;
    if (camera.getTrackingState() == TrackingState.PAUSED) {
      if (camera.getTrackingFailureReason() == TrackingFailureReason.NONE) {
        message = SEARCHING_PLANE_MESSAGE;
      } else {
        message = TrackingStateHelper.getTrackingFailureReasonString(camera);
      }
    } else if (hasTrackingPlane()) {
      if (wrappedAnchors.isEmpty()) {
        message = WAITING_FOR_TAP_MESSAGE;
      }
    } else {
      message = SEARCHING_PLANE_MESSAGE;
    }
    if (message == null) {
      messageSnackbarHelper.hide(this);
    } else {
      messageSnackbarHelper.showMessage(this, message);
    }

    // -- Draw background

    if (frame.getTimestamp() != 0) {
      // Suppress rendering if the camera did not produce the first frame yet. This is to avoid
      // drawing possible leftover data from previous sessions if the texture is reused.
      backgroundRenderer.drawBackground(render);
    }

    // If not tracking, don't draw 3D objects.
    if (camera.getTrackingState() == TrackingState.PAUSED) {
      return;
    }

    // -- Draw non-occluded virtual objects (planes, point cloud)

    // Get projection matrix.
    camera.getProjectionMatrix(projectionMatrix, 0, Z_NEAR, Z_FAR);

    // Get camera matrix and draw.
    camera.getViewMatrix(viewMatrix, 0);

    // Visualize tracked points.
    // Use try-with-resources to automatically release the point cloud.
    try (PointCloud pointCloud = frame.acquirePointCloud()) {
      if (pointCloud.getTimestamp() > lastPointCloudTimestamp) {
        pointCloudVertexBuffer.set(pointCloud.getPoints());
        lastPointCloudTimestamp = pointCloud.getTimestamp();
      }
      Matrix.multiplyMM(modelViewProjectionMatrix, 0, projectionMatrix, 0, viewMatrix, 0);
      pointCloudShader.setMat4("u_ModelViewProjection", modelViewProjectionMatrix);
      render.draw(pointCloudMesh, pointCloudShader);
    }

    // Visualize planes.
    planeRenderer.drawPlanes(
        render,
        session.getAllTrackables(Plane.class),
        camera.getDisplayOrientedPose(),
        projectionMatrix);

    // -- Draw occluded virtual objects

    // Update lighting parameters in the shader
    updateLightEstimation(frame.getLightEstimate(), viewMatrix);

    // Visualize anchors created by touch.
    render.clear(virtualSceneFramebuffer, 0f, 0f, 0f, 0f);
    for (WrappedAnchor wrappedAnchor : wrappedAnchors) {
      Anchor anchor = wrappedAnchor.getAnchor();
      Trackable trackable = wrappedAnchor.getTrackable();
      if (anchor.getTrackingState() != TrackingState.TRACKING) {
        continue;
      }

      // Get the current pose of an Anchor in world space. The Anchor pose is updated
      // during calls to session.update() as ARCore refines its estimate of the world.
      anchor.getPose().toMatrix(modelMatrix, 0);

      // Calculate model/view/projection matrices
      Matrix.multiplyMM(modelViewMatrix, 0, viewMatrix, 0, modelMatrix, 0);
      Matrix.multiplyMM(modelViewProjectionMatrix, 0, projectionMatrix, 0, modelViewMatrix, 0);

      // Update shader properties and draw
      virtualObjectShader.setMat4("u_ModelView", modelViewMatrix);
      virtualObjectShader.setMat4("u_ModelViewProjection", modelViewProjectionMatrix);

      if (trackable instanceof InstantPlacementPoint
          && ((InstantPlacementPoint) trackable).getTrackingMethod()
              == InstantPlacementPoint.TrackingMethod.SCREENSPACE_WITH_APPROXIMATE_DISTANCE) {
        virtualObjectShader.setTexture(
            "u_AlbedoTexture", virtualObjectAlbedoInstantPlacementTexture);
      } else {
        virtualObjectShader.setTexture("u_AlbedoTexture", virtualObjectAlbedoTexture);
      }

      render.draw(virtualObjectMesh, virtualObjectShader, virtualSceneFramebuffer);
    }

    // Compose the virtual scene with the background.
    backgroundRenderer.drawVirtualScene(render, virtualSceneFramebuffer, Z_NEAR, Z_FAR);
  }

  /** Checks if we detected at least one plane. */
  private boolean hasTrackingPlane() {
    for (Plane plane : session.getAllTrackables(Plane.class)) {
      if (plane.getTrackingState() == TrackingState.TRACKING) {
        return true;
      }
    }
    return false;
  }

  /** Update state based on the current frame's light estimation. */
  private void updateLightEstimation(LightEstimate lightEstimate, float[] viewMatrix) {
    if (lightEstimate.getState() != LightEstimate.State.VALID) {
      virtualObjectShader.setBool("u_LightEstimateIsValid", false);
      return;
    }
    virtualObjectShader.setBool("u_LightEstimateIsValid", true);

    Matrix.invertM(viewInverseMatrix, 0, viewMatrix, 0);
    virtualObjectShader.setMat4("u_ViewInverse", viewInverseMatrix);

    updateMainLight(
        lightEstimate.getEnvironmentalHdrMainLightDirection(),
        lightEstimate.getEnvironmentalHdrMainLightIntensity(),
        viewMatrix);
    updateSphericalHarmonicsCoefficients(
        lightEstimate.getEnvironmentalHdrAmbientSphericalHarmonics());
    cubemapFilter.update(lightEstimate.acquireEnvironmentalHdrCubeMap());
  }

  private void updateMainLight(float[] direction, float[] intensity, float[] viewMatrix) {
    // We need the direction in a vec4 with 0.0 as the final component to transform it to view space
    worldLightDirection[0] = direction[0];
    worldLightDirection[1] = direction[1];
    worldLightDirection[2] = direction[2];
    Matrix.multiplyMV(viewLightDirection, 0, viewMatrix, 0, worldLightDirection, 0);
    virtualObjectShader.setVec4("u_ViewLightDirection", viewLightDirection);
    virtualObjectShader.setVec3("u_LightIntensity", intensity);
  }

  private void updateSphericalHarmonicsCoefficients(float[] coefficients) {
    // Pre-multiply the spherical harmonics coefficients before passing them to the shader. The
    // constants in sphericalHarmonicFactors were derived from three terms:
    //
    // 1. The normalized spherical harmonics basis functions (y_lm)
    //
    // 2. The lambertian diffuse BRDF factor (1/pi)
    //
    // 3. A <cos> convolution. This is done to so that the resulting function outputs the irradiance
    // of all incoming light over a hemisphere for a given surface normal, which is what the shader
    // (environmental_hdr.frag) expects.
    //
    // You can read more details about the math here:
    // https://google.github.io/filament/Filament.html#annex/sphericalharmonics

    if (coefficients.length != 9 * 3) {
      throw new IllegalArgumentException(
          "The given coefficients array must be of length 27 (3 components per 9 coefficients");
    }

    // Apply each factor to every component of each coefficient
    for (int i = 0; i < 9 * 3; ++i) {
      sphericalHarmonicsCoefficients[i] = coefficients[i] * sphericalHarmonicFactors[i / 3];
    }
    virtualObjectShader.setVec3Array(
        "u_SphericalHarmonicsCoefficients", sphericalHarmonicsCoefficients);
  }

  /** Configures the session with feature settings. */
  private void configureSession() {
    Config config = session.getConfig();
    config.setLightEstimationMode(Config.LightEstimationMode.ENVIRONMENTAL_HDR);
    if (session.isDepthModeSupported(Config.DepthMode.AUTOMATIC)) {
      config.setDepthMode(Config.DepthMode.AUTOMATIC);
    } else {
      config.setDepthMode(Config.DepthMode.DISABLED);
    }
    if (instantPlacementSettings.isInstantPlacementEnabled()) {
      config.setInstantPlacementMode(InstantPlacementMode.LOCAL_Y_UP);
    } else {
      config.setInstantPlacementMode(InstantPlacementMode.DISABLED);
    }
    session.configure(config);
  }
}

/**
 * Associates an Anchor with the trackable it was attached to. This is used to be able to check
 * whether or not an Anchor originally was attached to an {@link InstantPlacementPoint}.
 */
class WrappedAnchor {
  private Anchor anchor;
  private Trackable trackable;

  public WrappedAnchor(Anchor anchor, Trackable trackable) {
    this.anchor = anchor;
    this.trackable = trackable;
  }

  public Anchor getAnchor() {
    return anchor;
  }

  public Trackable getTrackable() {
    return trackable;
  }
}
