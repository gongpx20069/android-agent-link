from __future__ import annotations

import io
import os
import re
import unicodedata
from collections.abc import Callable
from typing import TYPE_CHECKING

if TYPE_CHECKING:
    from rich.console import Console, ConsoleOptions, RenderResult


def display_text(value: str) -> str:
    # Treat remote control sequences as data, never terminal instructions.
    value = re.sub(r"\x1b\][^\x07\x1b]*(?:\x07|\x1b\\)", "", value)
    value = re.sub(r"\x1b\[[0-?]*[ -/]*[@-~]", "", value)
    return "".join(c for c in value if c in "\n\t" or not unicodedata.category(c).startswith("C"))


class MarkdownStream:
    MAX_PENDING = 64 * 1024

    def __init__(
        self, write: Callable[[str], None], notice: Callable[[str], None], width: Callable[[], int],
    ) -> None:
        from markdown_it import MarkdownIt
        from rich.markdown import Markdown, TableElement
        from rich.table import Table

        class WrappingTable(TableElement):
            def __rich_console__(self, console: Console, options: ConsoleOptions) -> RenderResult:
                for table in super().__rich_console__(console, options):
                    if isinstance(table, Table):
                        for column in table.columns:
                            column.overflow = "fold"
                    yield table

        class TerminalMarkdown(Markdown):
            elements = {**Markdown.elements, "table_open": WrappingTable}

        self.write = write
        self.notice = notice
        self.width = width
        self.parser = MarkdownIt().enable("strikethrough").enable("table")
        self.markdown_type = TerminalMarkdown
        self.pending = ""
        self.plain = False
        self._layout_warned = False

    def feed(self, text: str) -> None:
        if self.plain:
            self.write(display_text(text))
            return
        if len(self.pending) + len(text) > self.MAX_PENDING:
            self.notice("Markdown display limit reached; the rest of this reply is shown as plain text.")
            self.write(display_text(self.pending))
            self.pending = ""
            self.plain = True
            self.write(display_text(text))
            return
        self.pending += text
        if "\n" not in text:
            return
        tokens = self.parser.parse(self.pending)
        roots = [token for token in tokens if token.level == 0 and token.map and token.nesting >= 0]
        if not roots:
            return
        last = roots[-1]
        assert last.map is not None
        lines = self.pending.splitlines(keepends=True)
        end = last.map[0]
        # The last block can still grow or change type (e.g. paragraph -> table).
        if last.type in {"paragraph_open", "table_open"} and re.search(r"\n[ \t]*\n$", self.pending):
            end = len(lines)
        elif last.type in {"heading_open", "hr"} and self.pending.endswith("\n"):
            end = last.map[1]
        elif last.type == "fence" and self.pending.endswith("\n") and last.map[1] - last.map[0] >= 2:
            closing = lines[last.map[1] - 1].rstrip("\r\n")
            if re.fullmatch(r" {0,3}" + re.escape(last.markup[0]) + "{" + str(len(last.markup)) + r",}[ \t]*", closing):
                end = last.map[1]
        if end:
            block = "".join(lines[:end])
            self.pending = "".join(lines[end:])
            self._render(block)

    def finish(self) -> None:
        if self.pending:
            self._render(self.pending)
        self.pending = ""
        self.plain = False
        self._layout_warned = False

    def _render(self, text: str) -> None:
        from rich.console import Console
        from rich.segment import Segment, Segments

        text = display_text(text)
        if not text.strip():
            return
        width = max(1, self.width())
        tokens = self.parser.parse(text)
        depth = max((token.level for token in tokens), default=0)
        columns = row_columns = 0
        for token in tokens:
            if token.type == "tr_open":
                row_columns = 0
            elif token.type in {"th_open", "td_open"}:
                row_columns += 1
                columns = max(columns, row_columns)
        if width < max(20, depth * 4 + 8, columns * 3 + 1):
            if not self._layout_warned:
                self.notice("Terminal too narrow for this Markdown layout; showing original text without truncation.")
                self._layout_warned = True
            self.write(text + ("" if text.endswith("\n") else "\n"))
            return
        output = io.StringIO()
        console = Console(
            file=output, width=width, force_terminal=True,
            color_system=None if os.environ.get("NO_COLOR") else "standard",
            markup=False, highlight=False, emoji=False,
        )
        markdown = self.markdown_type(text, hyperlinks=False, code_theme="ansi_light" if os.environ.get("NO_COLOR") else "monokai")
        # Sanitize after parsing too: Markdown entities can decode into controls.
        segments = (
            Segment(display_text(segment.text), segment.style)
            for segment in console.render(markdown) if not segment.control
        )
        console.print(Segments(segments), end="")
        self.write(output.getvalue())
