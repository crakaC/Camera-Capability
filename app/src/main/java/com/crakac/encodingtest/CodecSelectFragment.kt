package com.crakac.encodingtest

import android.media.MediaCodecInfo
import android.media.MediaCodecList
import android.os.Build
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.fragment.app.Fragment
import androidx.navigation.Navigation
import androidx.recyclerview.widget.DividerItemDecoration
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.crakac.encodingtest.util.GenericListAdapter
import com.crakac.encodingtest.util.Util
import com.google.android.material.snackbar.Snackbar
import java.util.*

class CodecSelectFragment : Fragment() {
    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ) = RecyclerView(requireContext())

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        view as RecyclerView
        view.apply {
            val lm = LinearLayoutManager(requireContext())
            addItemDecoration(DividerItemDecoration(requireContext(), lm.orientation))
            layoutManager = lm
            val codecList = enumerateVideoCodecs()
            adapter = GenericListAdapter(
                codecList,
                android.R.layout.simple_list_item_1
            ) { view, data, _ ->
                view.findViewById<TextView>(android.R.id.text1).text = data.toFormattedString()
                view.setOnClickListener {
                    Util.saveCodec(data.name)
                    Navigation.findNavController(requireActivity(), R.id.fragment_container)
                        .popBackStack()
                    Snackbar.make(view, "Codec is set to ${data.name}", Snackbar.LENGTH_SHORT)
                        .show()
                }
            }
        }
    }

    companion object {
        private val targetTypes = arrayOf("video/avc", "video/hevc")
        private fun enumerateVideoCodecs() =
            MediaCodecList(MediaCodecList.ALL_CODECS).codecInfos.filter { codec ->
                codec.isEncoder && codec.supportedTypes.any { it.lowercase() in targetTypes }
            }

        fun MediaCodecInfo.toFormattedString(): String {
            val ret = mutableListOf<String>()
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q){
                ret += "$name($canonicalName)"
                if (isHardwareAccelerated) ret += "🔥HW Accelerated"
                if (isVendor) ret += "\uD83D\uDD27Vendor specific codec"
            } else {
                ret += name
            }
            ret += "\uD83C\uDFA5" + supportedTypes.joinToString(", ")
            return ret.joinToString("\n")
        }
    }
}