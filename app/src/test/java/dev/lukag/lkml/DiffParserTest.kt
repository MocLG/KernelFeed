package dev.lukag.lkml

import dev.lukag.lkml.domain.model.BodyBlock
import dev.lukag.lkml.domain.model.DiffFileMode
import dev.lukag.lkml.domain.model.DiffLineKind
import dev.lukag.lkml.domain.parser.BodyParser
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class DiffParserTest {

    @Test
    fun `parses a git patch into files and hunks`() {
        val body = """
            Fix the thing.

            Signed-off-by: Alice <alice@example.com>
            ---
             mm/slab.c | 4 ++--
             1 file changed, 2 insertions(+), 2 deletions(-)

            diff --git a/mm/slab.c b/mm/slab.c
            index 8cdc4fda89e9..59341b5a0c05 100644
            --- a/mm/slab.c
            +++ b/mm/slab.c
            @@ -2097,7 +2097,7 @@ void slab_thing(struct kmem_cache *c)
             	int ret;

            -	old_call(c);
            +	new_call(c);

             	return ret;
             }
        """.trimIndent()

        val blocks = BodyParser.parse(body)
        val diff = blocks.filterIsInstance<BodyBlock.Diff>().single().file

        assertEquals("mm/slab.c", diff.displayPath)
        assertEquals(DiffFileMode.MODIFIED, diff.mode)
        assertEquals(1, diff.hunks.size)
        assertEquals(1, diff.addedLines)
        assertEquals(1, diff.removedLines)

        val hunk = diff.hunks.single()
        assertEquals(2097, hunk.oldStart)
        assertEquals(7, hunk.oldCount)
        assertEquals("void slab_thing(struct kmem_cache *c)", hunk.section)
    }

    /**
     * The core robustness property: hunk length comes from the `@@` counts, so prose that
     * happens to start with `-` after a patch is not swallowed as a deletion.
     */
    @Test
    fun `stops the hunk at its declared line counts`() {
        val body = """
            diff --git a/f.c b/f.c
            --- a/f.c
            +++ b/f.c
            @@ -1,2 +1,2 @@
            -old
            +new
             ctx
            - this is prose, not a deletion
            and more prose
        """.trimIndent()

        val blocks = BodyParser.parse(body)
        val diff = blocks.filterIsInstance<BodyBlock.Diff>().single().file
        assertEquals(3, diff.hunks.single().lines.size)

        val prose = blocks.filterIsInstance<BodyBlock.Prose>().joinToString("\n") { it.text }
        assertTrue("prose after the hunk must survive", prose.contains("this is prose"))
        assertTrue(prose.contains("and more prose"))
    }

    @Test
    fun `recognises added and deleted files via dev null`() {
        val added = BodyParser.parse(
            """
            diff --git a/new.c b/new.c
            new file mode 100644
            --- /dev/null
            +++ b/new.c
            @@ -0,0 +1,2 @@
            +int main(void)
            +{ return 0; }
            """.trimIndent(),
        ).filterIsInstance<BodyBlock.Diff>().single().file

        assertEquals(DiffFileMode.ADDED, added.mode)
        assertEquals("new.c", added.displayPath)
        assertEquals(2, added.addedLines)
    }

    @Test
    fun `quoted patches stay quotes and are not rendered as live diffs`() {
        val body = """
            > diff --git a/f.c b/f.c
            > --- a/f.c
            > +++ b/f.c
            > @@ -1,1 +1,1 @@
            > -old
            > +new

            Looks good to me.
        """.trimIndent()

        val blocks = BodyParser.parse(body)
        assertTrue(blocks.none { it is BodyBlock.Diff })
        assertEquals(1, blocks.filterIsInstance<BodyBlock.Quote>().size)
        assertEquals(1, blocks.first { it is BodyBlock.Quote }.let { (it as BodyBlock.Quote).depth })
    }

    @Test
    fun `parses diffstat and trailers separately from prose`() {
        val body = """
            Some explanation.

            Signed-off-by: Bob <bob@example.com>
            Reviewed-by: Carol <carol@example.com>
            Fixes: deadbeef1234 ("subsys: earlier change")
            ---
             a/x.c | 2 +-
             b/y.c | 8 ++++++--
             2 files changed, 7 insertions(+), 3 deletions(-)
        """.trimIndent()

        val blocks = BodyParser.parse(body)
        val trailers = blocks.filterIsInstance<BodyBlock.Trailers>().single()
        assertEquals(3, trailers.entries.size)
        assertEquals("Signed-off-by", trailers.entries.first().key)

        val stat = blocks.filterIsInstance<BodyBlock.DiffStat>().single()
        assertEquals(2, stat.lines.size)
        assertEquals("2 files changed, 7 insertions(+), 3 deletions(-)", stat.summary)
    }

    @Test
    fun `handles a truncated patch without losing earlier hunks`() {
        val body = """
            diff --git a/f.c b/f.c
            --- a/f.c
            +++ b/f.c
            @@ -1,3 +1,3 @@
             a
            -b
            +c
            @@ -50,4 +50,4 @@
             x
            -y
        """.trimIndent()

        val diff = BodyParser.parse(body).filterIsInstance<BodyBlock.Diff>().single().file
        assertEquals(2, diff.hunks.size)
        assertEquals(3, diff.hunks[0].lines.size)
        // The second hunk claimed four lines but the mail ended; keep what arrived.
        assertEquals(2, diff.hunks[1].lines.size)
        assertEquals(DiffLineKind.REMOVED, diff.hunks[1].lines.last().kind)
    }

    @Test
    fun `signature terminates the body`() {
        val blocks = BodyParser.parse("Real content\n\n-- \nAlice\nSome Corp")
        assertEquals("Alice\nSome Corp", blocks.filterIsInstance<BodyBlock.Signature>().single().text)
    }
}
