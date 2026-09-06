package com.example.homeworktracker

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.fragment.app.Fragment
import com.google.android.material.appbar.MaterialToolbar

class PlaceholderFragment : Fragment() {

    companion object {
        private const val ARG_TITLE = "title"
        fun newInstance(title: String) = PlaceholderFragment().apply {
            arguments = Bundle().apply { putString(ARG_TITLE, title) }
        }
    }

    override fun onCreateView(
        inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?
    ): View = inflater.inflate(R.layout.fragment_placeholder, container, false)

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        val title = arguments?.getString(ARG_TITLE) ?: ""
        view.findViewById<MaterialToolbar>(R.id.toolbar).title = title
    }
}
