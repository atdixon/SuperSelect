Use It
---

Go to [http://superselect.rocks](http://superselect.rocks).

Develop It
---

First install [lein](https://leiningen.org/).

Then, to develop the popup, background, and workspace pages:

    $ rlwrap lein fig-dev

To develop the content script:

    $ lein content-dev
    
Then, launch a test browser:

    $ ./scripts/launch-test-browser.sh

To develop the docs, first 
[install Jekyll, etc](https://help.github.com/articles/setting-up-your-github-pages-site-locally-with-jekyll/).

Then:

    $ cd docs
    $ bundle exec jekyll serve



