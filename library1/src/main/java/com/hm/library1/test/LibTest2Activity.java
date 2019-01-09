package com.hm.library1.test;

import android.app.Activity;
import android.os.Bundle;
import android.support.annotation.Nullable;
import android.widget.TextView;

import com.hm.library1.R;
import com.hm.library1.R2;

import butterknife.BindView;
import butterknife.ButterKnife;

/**
 * Created by hjy on 2019/1/7.
 */

public class LibTest2Activity extends Activity {

    @BindView(R2.id.tv_library_test1)
    TextView mTvTest;

    @Override
    protected void onCreate(@Nullable Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_library_test1);
        ButterKnife.bind(this);
    }
}
