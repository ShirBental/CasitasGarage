package home.casita.security.access;

import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.annotation.DirtiesContext;

/** The access flows with the file store (local development). */
@SpringBootTest
@Import(TestTailnet.Config.class)
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_EACH_TEST_METHOD)
class FileAccessFlowTest extends AccessFlowContract {}
