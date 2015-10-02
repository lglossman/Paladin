package com.nanospark.gard.ui.custom;

/**
 * Created by cristian on 23/09/15.
 */
public abstract class BaseFragment extends mobi.tattu.utils.fragments.BaseFragment {

    public int getColorFromResource(int color){
        return getBaseActivity().getColorFromResource(color);
    }

    public BaseActivity getBaseActivity(){
        return (BaseActivity)getActivity();
    }

}
