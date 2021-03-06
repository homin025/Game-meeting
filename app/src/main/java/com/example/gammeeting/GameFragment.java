package com.example.gammeeting;

import android.app.ActivityManager;
import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.media.MediaPlayer;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.util.Log;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Toast;

import androidx.annotation.Nullable;

import com.google.ar.core.AugmentedImage;
import com.google.ar.core.AugmentedImageDatabase;
import com.google.ar.core.Config;
import com.google.ar.core.Frame;
import com.google.ar.core.Session;
import com.google.ar.sceneform.AnchorNode;
import com.google.ar.sceneform.FrameTime;
import com.google.ar.sceneform.math.Vector3;
import com.google.ar.sceneform.rendering.Color;
import com.google.ar.sceneform.rendering.ExternalTexture;
import com.google.ar.sceneform.rendering.ModelRenderable;
import com.google.ar.sceneform.ux.ArFragment;

import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;

public class GameFragment extends ArFragment {
    private static final String TAG = "GameFragment";
    private static final double MIN_OPENGL_VERSION = 3.0;

    public HashMap<String, Idol> fileNames = new HashMap<>();

    public MediaPlayer mediaPlayer;
    public ExternalTexture texture;
    public ModelRenderable videoRenderable;

    GameEventListener listener;

    String currTrackingImageName = "";
    boolean trackingDone = false;

    boolean instructionDone;

    public interface GameEventListener {
        void onMarkerFound(Idol idol);
        void onVideoStarted();
        // 게임들 끝났을 떄 (가위바위보 내는 타이밍, 참참참 타이밍) 호출되는 이벤트메소드 추가하기
    }

    public static GameFragment newInstance() {
        return new GameFragment();
    }

    public static class Idol {
        public String idolName;
        public String idolHandType;

        Idol(String name, String handType) {
            this.idolName = name;
            this.idolHandType = handType;
        }

        public String getName() { return this.idolName; }
        public String getHandType() { return this.idolHandType; }
    }

    @Override
    public void onAttach(Context context) {
        super.onAttach(context);

        // Check for Sceneform being supported on this device.  This check will be integrated into
        // Sceneform eventually./
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.N)
            Log.e(TAG, "Sceneform requires Android N or later");

        String openGlVersionString =
                ((ActivityManager) context.getSystemService(Context.ACTIVITY_SERVICE))
                        .getDeviceConfigurationInfo()
                        .getGlEsVersion();

        if (Double.parseDouble(openGlVersionString) < MIN_OPENGL_VERSION)
            Log.e(TAG, "Sceneform requires OpenGL ES 3.0 or later");

        listener = (GameEventListener)context;
    }

    @Override
    public View onCreateView(
            LayoutInflater inflater, @Nullable ViewGroup container, @Nullable Bundle savedInstanceState) {
        View view = super.onCreateView(inflater, container, savedInstanceState);

        texture = new ExternalTexture();
        mediaPlayer = new MediaPlayer();

        ModelRenderable.builder()
                .setSource(getContext(), R.raw.video_screen)
                .build()
                .thenAccept(modelRenderable -> {
                    videoRenderable = modelRenderable;
                    videoRenderable.getMaterial().setExternalTexture("videoTexture", texture);
                    videoRenderable.getMaterial().setFloat4("keyColor", new Color(0.01843f, 1.0f, 0.098f));
                });

        // Turn off the plane discovery since we're only looking for images
        getPlaneDiscoveryController().hide();
        getPlaneDiscoveryController().setInstructionView(null);
        getArSceneView().getPlaneRenderer().setEnabled(false);
        getArSceneView().getScene().addOnUpdateListener(this::onUpdateFrame);

        return view;
    }

    @Override protected Config getSessionConfiguration(Session session){
        getPlaneDiscoveryController().setInstructionView(null);

        Config config = new Config(session);
        config.setUpdateMode(Config.UpdateMode.LATEST_CAMERA_IMAGE);

        setupAugmentedImageDatabase(config, session);

        session.configure(config);
        getArSceneView().setupSession(session);

        return config;
    }

    private boolean setupAugmentedImageDatabase(Config config, Session session) {
        fileNames.put("logo_exo.jpg", new Idol("EXO", "rock"));
        fileNames.put("logo_nct.jpg", new Idol("NCT127", "scissors"));
        fileNames.put("logo_redvelvet.jpg", new Idol("RED VELVET", "paper"));

        AugmentedImageDatabase augmentedImageDatabase = new AugmentedImageDatabase(session);
        ArrayList<Bitmap> augmentedImageBitmap = new ArrayList<>();

        for(String imageName: fileNames.keySet()) {
            try (InputStream inputStream = getActivity().getAssets().open(imageName)) {
                // 3번째 인수는 widthInMeters 로, 이미지의 물리적 너비를 추가함으로써 감지 지연시간을 감소시킴
                augmentedImageDatabase.addImage(imageName, BitmapFactory.decodeStream(inputStream), (float).5);
            } catch (IOException e) {
                Log.e("error", "IOError on loading Bitmap");
                return false;
            }
        }

        if (augmentedImageBitmap == null)
            return false;

        config.setAugmentedImageDatabase(augmentedImageDatabase);

        return true;
    }


    public void onUpdateFrame(FrameTime frameTime) {
        Frame frame = getArSceneView().getArFrame();
        if(frame == null || !instructionDone)
            return;

        Collection<AugmentedImage> updatedAugmentedImages =
                frame.getUpdatedTrackables(AugmentedImage.class);

        for (AugmentedImage augmentedImage : updatedAugmentedImages) {
            Idol idol = fileNames.get(augmentedImage.getName());
            if(!currTrackingImageName.equals(idol.getName()) || trackingDone) {
                trackingDone = true;
                setVideo(idol);
                currTrackingImageName = idol.getName();
            }

            switch (augmentedImage.getTrackingState()) {
                case TRACKING:
                    if (trackingDone) {
                        listener.onMarkerFound(idol);
                        trackingDone = false;
                    }
                    getArSceneView().getScene().addChild(createVideoNode(augmentedImage));
                    break;
                case STOPPED:
                    break;
            }
            break;
        }
    }

    private AnchorNode createVideoNode(AugmentedImage augmentedImage) {
        AnchorNode anchorNode = new AnchorNode(augmentedImage.createAnchor(augmentedImage.getCenterPose()));

        new Handler().postDelayed((Runnable) () -> mediaPlayer.start(), 3000);

        texture.getSurfaceTexture().setOnFrameAvailableListener(surfaceTexture -> {
            anchorNode.setRenderable(videoRenderable);
            anchorNode.setLocalScale(new Vector3(
                    augmentedImage.getExtentX(), 1.0f, augmentedImage.getExtentZ()));
        });

        return anchorNode;
    }

    public void setVideo(Idol idol) {
        String name = idol.getName();

        switch (name) {
            case "EXO":
                mediaPlayer = MediaPlayer.create(getContext(), R.raw.video_rock);
                break;
            case "NCT127":
                mediaPlayer = MediaPlayer.create(getContext(), R.raw.video_scissors);
                break;
            case "RED VELVET":
                mediaPlayer = MediaPlayer.create(getContext(), R.raw.video_paper);
                break;
        }

        mediaPlayer.setSurface(texture.getSurface());
        mediaPlayer.setLooping(false);
    }

    public void changeVideo() {
        texture = new ExternalTexture();
        mediaPlayer = MediaPlayer.create(getContext(), R.raw.video_alpha);
        mediaPlayer.setSurface(texture.getSurface());
        videoRenderable.getMaterial().setExternalTexture("videoTexture", texture);
    }

    public void setInstructionDone(boolean isInstructionDone) {
        instructionDone = isInstructionDone;
    }

    // 손 표시 방식이 2d로 결정됨
//    public void showHand(int type) {
//        Frame frame = getArSceneView().getArFrame();
//        if (frame == null)
//            return;
//
//        Pose pos = frame.getCamera().getPose().compose(Pose.makeTranslation(0, 0, -0.3f));
//        Anchor anchor = getArSceneView().getSession().createAnchor(pos);
//        AnchorNode anchorNode = new AnchorNode(anchor);
//        anchorNode.setParent(getArSceneView().getScene());
//
//        Node hand = new Node();
//        hand.setParent(anchorNode);
//
//        Renderable handRenderable;
//        switch(type) {
//            case DetectFragment.DetectEventListener.ROCK:
//                handRenderable = null;
//                break;
//            case DetectFragment.DetectEventListener.SCISSORS:
//                handRenderable = null;
//                break;
//            case DetectFragment.DetectEventListener.PAPER:
//                handRenderable = null;
//                break;
//            default:
//                Log.e("GameFragment","Improper type of hand");
//                return;
//        }
//        hand.setRenderable(handRenderable);
//
//        // TODO: 매 프레임마다 손 계속 생성하지 않도록 DetectFragment 혹은 GameFragment 둘 중 하나에서 조정
//        // TODO: GameFragment 생성 시 가위, 바위, 보 손 모델링 미리 로드해놓기, 적절하게 회전해놓기
//    }
//
//    public void removeHand() {
//        // TODO: showHand에서 만든 앵커 삭제, 앵커 가져올 수 있는 방법 구글링
//    }
}