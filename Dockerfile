FROM beakerx/beakerx:1.1.0

# Download Lein script
USER root
ADD https://raw.githubusercontent.com/technomancy/leiningen/2.8.1/bin/lein /usr/local/bin/lein
RUN chmod a+rx /usr/local/bin/lein
USER beakerx

# Install lein
RUN /bin/bash -c "source activate beakerx && lein && source deactivate"

# Install Packages
USER root
RUN apt-get update && apt-get install -y \
    gcc \
    graphviz \
    g++ \
    texlive-xetex \
    && rm -rf /var/lib/apt/lists/*
USER beakerx

# Install Clojure dependencies
RUN mkdir -p /home/beakerx/ppdsp/target
COPY project.clj /home/beakerx/ppdsp/project.clj
RUN /bin/bash -c "source activate beakerx && cd /home/beakerx/ppdsp/ && lein deps && source deactivate"

# Install Python dependencies
RUN /opt/conda/envs/beakerx/bin/python -m pip install --upgrade pip

RUN /opt/conda/envs/beakerx/bin/python -m pip install pandas  scikit-learn  numpy  orange3 PyQt5  scipy scikit-posthocs Cython -i https://pypi.tuna.tsinghua.edu.cn/simple/

# Install Machine-Optimized Libraries for Smile
USER root
RUN apt-get update && apt-get install -y \
    libblas3 \
    liblapack3 \
    && rm -rf /var/lib/apt/lists/*
USER beakerx

# Copy across beakerx.json config
COPY beakerx.json /home/beakerx/.jupyter/beakerx.json

WORKDIR /home/beakerx/ppdsp/notebooks
