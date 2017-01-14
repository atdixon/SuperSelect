### Demo It

Demo SuperSelect [here](http://superselect.rocks).

### Install It

Install SuperSelect [here](https://chrome.google.com/webstore/detail/superselect/pflcicgpnhmpkgkdaabodbbfhejigokh).

### Develop It

To develop the popup, background, and workspace pages:

    $ rlwrap lein fig-dev

To develop the content script:

    $ lein content-dev
    
Then, launch a test browser:

    $ ./scripts/launch-test-browser.sh

To develop just the content script capability headless (without
the browser extension environment):

    $ rlwrap lein sandbox

Then serve resources/unpacked/sandbox.html in your browser.

This is useful for better repl experience; figwheel isn't supported
for the content script in the extension environment.

##### Develop the Demo
 
First [install Jekyll, etc](https://help.github.com/articles/setting-up-your-github-pages-site-locally-with-jekyll/). Then:

    $ cd docs
    $ bundle exec jekyll serve



