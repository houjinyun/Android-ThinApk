package com.hm.iou.thinapk.demo;

import android.content.Intent;
import android.os.Bundle;
import android.support.v7.app.AppCompatActivity;
import android.view.View;
import android.widget.Button;

import com.hm.library1.LibTest1Activity;

import butterknife.BindView;
import butterknife.ButterKnife;
import butterknife.OnClick;

public class MainActivity extends AppCompatActivity {

    @BindView(R2.id.btn_start)
    Button mBtnStart;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);
        ButterKnife.bind(this);
    }

    @OnClick(value = {R2.id.btn_start, R2.id.btn_close, R2.id.btn_send})
    void onClick(View v) {
        if (v.getId() == R.id.btn_start) {
            startActivity(new Intent(this, LibTest1Activity.class));
            int i = com.hm.library1.R.id.action0;
        }
    }

}